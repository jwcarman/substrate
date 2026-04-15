/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.substrate.core.mailbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.SubscriberConfig;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;
import tools.jackson.databind.json.JsonMapper;

class DefaultMailboxTest {

  private static final String KEY = "substrate:mailbox:test";

  private static final Codec<String> STRING_CODEC =
      new Codec<>() {
        @Override
        public byte[] encode(String value) {
          return value.getBytes(UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
          return new String(bytes, UTF_8);
        }
      };

  private static final CodecFactory CODEC_FACTORY =
      new JacksonCodecFactory(JsonMapper.builder().build());

  private final ShutdownCoordinator coordinator = new ShutdownCoordinator();
  private InMemoryMailboxSpi spi;
  private Notifier notifier;
  private DefaultMailbox<String> mailbox;

  @BeforeEach
  void setUp() {
    spi = new InMemoryMailboxSpi();
    notifier = new DefaultNotifier(new InMemoryNotifier(), CODEC_FACTORY);
    spi.create(KEY, Duration.ofMinutes(5));
    mailbox =
        new DefaultMailbox<>(
            spi, KEY, STRING_CODEC, PayloadTransformer.IDENTITY, notifier, coordinator);
  }

  @Test
  void keyReturnsTheBoundKey() {
    assertThat(mailbox.key()).isEqualTo(KEY);
  }

  @Test
  void deliverStoresValueAndNotifies() {
    mailbox.deliver("hello");

    assertThat(spi.get(KEY)).contains("hello".getBytes(UTF_8));
  }

  @Test
  void subscribeReturnsValueImmediatelyIfAlreadyDelivered() {
    mailbox.deliver("hello");

    BlockingSubscription<String> sub = mailbox.subscribe();
    NextResult<String> result = sub.next(Duration.ofSeconds(1));
    assertThat(result).isInstanceOf(NextResult.Value.class);
    assertThat(((NextResult.Value<String>) result).value()).isEqualTo("hello");
  }

  @Test
  void subscribeReturnsValueWhenDeliveredLater() throws InterruptedException {
    CountDownLatch subscribed = new CountDownLatch(1);
    AtomicReference<NextResult<String>> result = new AtomicReference<>();

    Thread.ofVirtual()
        .start(
            () -> {
              BlockingSubscription<String> sub = mailbox.subscribe();
              subscribed.countDown();
              result.set(sub.next(Duration.ofSeconds(5)));
            });

    assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

    mailbox.deliver("world");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(result.get()).isNotNull();
              assertThat(result.get()).isInstanceOf(NextResult.Value.class);
              assertThat(((NextResult.Value<String>) result.get()).value()).isEqualTo("world");
            });
  }

  @Test
  void subscribeReturnsTimeoutWhenNoDelivery() {
    BlockingSubscription<String> sub = mailbox.subscribe();
    NextResult<String> result = sub.next(Duration.ofMillis(50));
    assertThat(result).isInstanceOf(NextResult.Timeout.class);
    sub.cancel();
  }

  @Test
  void subscribeReturnsCompletedAfterValue() {
    mailbox.deliver("hello");

    BlockingSubscription<String> sub = mailbox.subscribe();
    NextResult<String> first = sub.next(Duration.ofSeconds(1));
    assertThat(first).isInstanceOf(NextResult.Value.class);

    NextResult<String> second = sub.next(Duration.ofSeconds(1));
    assertThat(second).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void subscriptionIsInactiveAfterCompleted() {
    mailbox.deliver("hello");

    BlockingSubscription<String> sub = mailbox.subscribe();
    sub.next(Duration.ofSeconds(1));
    sub.next(Duration.ofSeconds(1));
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void deleteDelegatesToSpi() {
    mailbox.deliver("hello");

    mailbox.delete();

    assertThatThrownBy(() -> spi.get(KEY)).isInstanceOf(MailboxExpiredException.class);
  }

  @Test
  void deleteBeforeDeliveryNotifiesSubscribers() {
    BlockingSubscription<String> sub = mailbox.subscribe();
    await().atMost(Duration.ofSeconds(2)).until(sub::isActive);
    mailbox.delete();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              NextResult<String> result = sub.next(Duration.ofMillis(50));
              assertThat(result).isInstanceOf(NextResult.Deleted.class);
            });
  }

  @Test
  void subscribeReturnsValueWhenNotificationArrivesForMatchingKey() {
    CountDownLatch readyToDeliver = new CountDownLatch(1);
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                readyToDeliver.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
              }
              mailbox.deliver("async-value");
            });

    BlockingSubscription<String> sub = mailbox.subscribe();
    readyToDeliver.countDown();
    NextResult<String> result = sub.next(Duration.ofSeconds(5));
    assertThat(result).isInstanceOf(NextResult.Value.class);
    assertThat(((NextResult.Value<String>) result).value()).isEqualTo("async-value");
  }

  @Test
  void notificationForDifferentKeyDoesNotTriggerDelivery() {
    notifier.notifyMailboxChanged("some-other-key");

    BlockingSubscription<String> sub = mailbox.subscribe();
    NextResult<String> result = sub.next(Duration.ofMillis(200));
    assertThat(result).isInstanceOf(NextResult.Timeout.class);
    sub.cancel();
  }

  @Test
  void secondDeliverThrowsMailboxFullException() {
    mailbox.deliver("first");

    assertThatThrownBy(() -> mailbox.deliver("second")).isInstanceOf(MailboxFullException.class);
  }

  @Test
  void subscribeReturnsOriginalValueAfterMailboxFullException() {
    mailbox.deliver("original");

    assertThatThrownBy(() -> mailbox.deliver("replacement"))
        .isInstanceOf(MailboxFullException.class);

    BlockingSubscription<String> sub = mailbox.subscribe();
    NextResult<String> result = sub.next(Duration.ofSeconds(1));
    assertThat(result).isInstanceOf(NextResult.Value.class);
    assertThat(((NextResult.Value<String>) result).value()).isEqualTo("original");
  }

  @Test
  void deleteAfterDeliveryStillWorks() {
    mailbox.deliver("value");
    mailbox.delete();

    assertThatThrownBy(() -> spi.get(KEY)).isInstanceOf(MailboxExpiredException.class);
  }

  @Test
  void callbackSubscribeFiresOnNextOnDelivery() throws InterruptedException {
    AtomicReference<String> captured = new AtomicReference<>();
    CountDownLatch delivered = new CountDownLatch(1);

    Subscription sub =
        mailbox.subscribe(
            (String value) -> {
              captured.set(value);
              delivered.countDown();
            });
    mailbox.deliver("callback-value");
    assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(captured.get()).isEqualTo("callback-value");
    sub.cancel();
  }

  @Test
  void callbackSubscribeFiresOnCompleteAfterOnNext() throws InterruptedException {
    CountDownLatch nextLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<String> order = new AtomicReference<>("");

    Subscription sub =
        mailbox.subscribe(
            (SubscriberConfig<String> cfg) ->
                cfg.onNext(
                        value -> {
                          order.updateAndGet(s -> s + "next,");
                          nextLatch.countDown();
                        })
                    .onCompleted(
                        () -> {
                          order.updateAndGet(s -> s + "complete");
                          completeLatch.countDown();
                        }));
    mailbox.deliver("value");
    assertThat(completeLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(order.get()).isEqualTo("next,complete");
    sub.cancel();
  }

  @Test
  void callbackSubscribeFiresOnDeleteWhenDeletedBeforeDelivery() throws InterruptedException {
    CountDownLatch deleteLatch = new CountDownLatch(1);
    AtomicReference<Boolean> onNextFired = new AtomicReference<>(false);

    Subscription sub =
        mailbox.subscribe(
            (SubscriberConfig<String> cfg) ->
                cfg.onNext(value -> onNextFired.set(true)).onDeleted(deleteLatch::countDown));
    await().atMost(Duration.ofSeconds(2)).until(sub::isActive);
    mailbox.delete();
    assertThat(deleteLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(onNextFired.get()).isFalse();
    sub.cancel();
  }

  @Test
  void callbackSubscribeFiresOnExpirationWhenTtlElapses() throws InterruptedException {
    InMemoryMailboxSpi shortTtlSpi = new InMemoryMailboxSpi();
    String shortKey = "substrate:mailbox:short";
    shortTtlSpi.create(shortKey, Duration.ofMillis(100));
    DefaultMailbox<String> shortMailbox =
        new DefaultMailbox<>(
            shortTtlSpi,
            shortKey,
            STRING_CODEC,
            PayloadTransformer.IDENTITY,
            notifier,
            coordinator);

    CountDownLatch expirationLatch = new CountDownLatch(1);
    AtomicReference<Boolean> onNextFired = new AtomicReference<>(false);

    Subscription sub =
        shortMailbox.subscribe(
            (SubscriberConfig<String> cfg) ->
                cfg.onNext(value -> onNextFired.set(true)).onExpired(expirationLatch::countDown));
    assertThat(expirationLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(onNextFired.get()).isFalse();
    sub.cancel();
  }

  @Test
  void blockingSubscribeReturnsExpiredWhenTtlElapses() {
    InMemoryMailboxSpi shortTtlSpi = new InMemoryMailboxSpi();
    String shortKey = "substrate:mailbox:short";
    shortTtlSpi.create(shortKey, Duration.ofMillis(100));
    DefaultMailbox<String> shortMailbox =
        new DefaultMailbox<>(
            shortTtlSpi,
            shortKey,
            STRING_CODEC,
            PayloadTransformer.IDENTITY,
            notifier,
            coordinator);

    BlockingSubscription<String> sub = shortMailbox.subscribe();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              NextResult<String> result = sub.next(Duration.ofMillis(50));
              assertThat(result).isInstanceOf(NextResult.Expired.class);
            });
  }

  @Test
  void cancelStopsFeederThread() {
    BlockingSubscription<String> sub = mailbox.subscribe();
    assertThat(sub.isActive()).isTrue();
    sub.cancel();
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void cancelOnAlreadyReceivedValueIsIdempotent() {
    mailbox.deliver("value");

    BlockingSubscription<String> sub = mailbox.subscribe();
    NextResult<String> result = sub.next(Duration.ofSeconds(1));
    assertThat(result).isInstanceOf(NextResult.Value.class);
    sub.cancel();
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void feederThreadExitsAfterSuccessfulPush() {
    mailbox.deliver("value");

    BlockingSubscription<String> sub = mailbox.subscribe();
    sub.next(Duration.ofSeconds(1));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              long feederCount =
                  Thread.getAllStackTraces().keySet().stream()
                      .filter(t -> t.getName().startsWith("substrate-mailbox-feeder"))
                      .filter(Thread::isAlive)
                      .count();
              assertThat(feederCount).isZero();
            });
  }

  private DefaultMailbox<String> connectedMailbox(MailboxSpi mockSpi) {
    return new DefaultMailbox<>(
        mockSpi, KEY, STRING_CODEC, PayloadTransformer.IDENTITY, notifier, coordinator, true);
  }

  @Test
  void connectSourcedSubscribeOnNonexistentThrowsMailboxNotFoundException() {
    MailboxSpi mockSpi = org.mockito.Mockito.mock(MailboxSpi.class);
    org.mockito.Mockito.when(mockSpi.exists(KEY)).thenReturn(false);
    DefaultMailbox<String> m = connectedMailbox(mockSpi);
    assertThatThrownBy(m::subscribe)
        .isInstanceOf(org.jwcarman.substrate.mailbox.MailboxNotFoundException.class);
  }

  @Test
  void connectSourcedDeliverOnNonexistentThrows() {
    MailboxSpi mockSpi = org.mockito.Mockito.mock(MailboxSpi.class);
    org.mockito.Mockito.when(mockSpi.exists(KEY)).thenReturn(false);
    DefaultMailbox<String> m = connectedMailbox(mockSpi);
    assertThatThrownBy(() -> m.deliver("x"))
        .isInstanceOf(org.jwcarman.substrate.mailbox.MailboxNotFoundException.class);
  }

  @Test
  void createSourcedHandleDoesNotProbe() {
    MailboxSpi mockSpi = org.mockito.Mockito.mock(MailboxSpi.class);
    DefaultMailbox<String> m =
        new DefaultMailbox<>(
            mockSpi, KEY, STRING_CODEC, PayloadTransformer.IDENTITY, notifier, coordinator);
    m.deliver("x");
    org.mockito.Mockito.verify(mockSpi, org.mockito.Mockito.never())
        .exists(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void probeFiresExactlyOnceAcrossManyOperations() {
    MailboxSpi mockSpi = org.mockito.Mockito.mock(MailboxSpi.class);
    org.mockito.Mockito.when(mockSpi.exists(KEY)).thenReturn(true);
    DefaultMailbox<String> m = connectedMailbox(mockSpi);
    m.deliver("x");
    m.deliver("y");
    org.mockito.Mockito.verify(mockSpi, org.mockito.Mockito.times(1)).exists(KEY);
  }

  @Test
  void connectSourcedDeleteDoesNotProbe() {
    MailboxSpi mockSpi = org.mockito.Mockito.mock(MailboxSpi.class);
    DefaultMailbox<String> m = connectedMailbox(mockSpi);
    m.delete();
    org.mockito.Mockito.verify(mockSpi, org.mockito.Mockito.never())
        .exists(org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(mockSpi).delete(KEY);
  }
}
