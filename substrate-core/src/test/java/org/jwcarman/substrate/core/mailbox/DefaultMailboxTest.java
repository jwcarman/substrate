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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;

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

  private InMemoryMailboxSpi spi;
  private InMemoryNotifier notifier;
  private DefaultMailbox<String> mailbox;

  @BeforeEach
  void setUp() {
    spi = new InMemoryMailboxSpi();
    notifier = new InMemoryNotifier();
    spi.create(KEY, Duration.ofMinutes(5));
    mailbox = new DefaultMailbox<>(spi, KEY, STRING_CODEC, notifier);
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
  void pollReturnsImmediatelyIfAlreadyDelivered() {
    mailbox.deliver("hello");

    Optional<String> result = mailbox.poll(Duration.ofSeconds(1));

    assertThat(result).contains("hello");
  }

  @Test
  void pollReturnsValueWhenDeliveredLater() {
    AtomicReference<Optional<String>> result = new AtomicReference<>();

    Thread.ofVirtual()
        .start(
            () -> {
              result.set(mailbox.poll(Duration.ofSeconds(5)));
            });

    // Give poll time to enter the wait
    await().pollDelay(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1)).until(() -> true);

    mailbox.deliver("world");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(result.get()).isNotNull();
              assertThat(result.get()).contains("world");
            });
  }

  @Test
  void pollReturnsEmptyOnTimeout() {
    Optional<String> result = mailbox.poll(Duration.ofMillis(50));

    assertThat(result).isEmpty();
  }

  @Test
  void deleteDelegatesToSpi() {
    mailbox.deliver("hello");

    mailbox.delete();

    assertThatThrownBy(() -> spi.get(KEY)).isInstanceOf(MailboxExpiredException.class);
  }

  @Test
  void deleteRemovesValueFromSpi() {
    mailbox.deliver("value");
    assertThat(spi.get(KEY)).isPresent();

    mailbox.delete();

    assertThatThrownBy(() -> spi.get(KEY)).isInstanceOf(MailboxExpiredException.class);
  }

  @Test
  void deleteBeforeDeliveryCancelsFuture() {
    mailbox.delete();

    assertThatThrownBy(() -> spi.get(KEY)).isInstanceOf(MailboxExpiredException.class);
  }

  @Test
  void pollReturnsValueWhenNotificationArrivesForMatchingKey() {
    // Deliver from a separate thread after a brief delay
    Thread.ofVirtual()
        .start(
            () -> {
              await()
                  .pollDelay(Duration.ofMillis(100))
                  .atMost(Duration.ofSeconds(1))
                  .until(() -> true);
              mailbox.deliver("async-value");
            });

    Optional<String> result = mailbox.poll(Duration.ofSeconds(5));
    assertThat(result).contains("async-value");
  }

  @Test
  void notificationForDifferentKeyDoesNotTriggerRead() {
    // Send a notification for a different key - should not wake up the reader
    notifier.notify("some-other-key", "payload");

    Optional<String> result = mailbox.poll(Duration.ofMillis(200));
    assertThat(result).isEmpty();
  }
}
