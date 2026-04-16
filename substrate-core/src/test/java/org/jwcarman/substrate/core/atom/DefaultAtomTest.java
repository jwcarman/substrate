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
package org.jwcarman.substrate.core.atom;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.atom.Snapshot;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import tools.jackson.databind.json.JsonMapper;

class DefaultAtomTest {

  private static final String KEY = "substrate:atom:test";
  private static final Duration TTL = Duration.ofSeconds(10);

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
  private InMemoryAtomSpi spi;
  private Notifier notifier;
  private DefaultAtom<String> atom;

  @BeforeEach
  void setUp() {
    spi = new InMemoryAtomSpi();
    notifier = new DefaultNotifier(new InMemoryNotifier(), CODEC_FACTORY);

    byte[] bytes = STRING_CODEC.encode("initial");
    String token = DefaultAtom.token(bytes);
    spi.create(KEY, bytes, token, TTL);

    atom = new DefaultAtom<>(context(spi), KEY, STRING_CODEC, false);
  }

  private AtomContext context(AtomSpi atomSpi) {
    return new AtomContext(
        atomSpi, PayloadTransformer.IDENTITY, notifier, Duration.ofHours(24), coordinator);
  }

  @Test
  void keyReturnsTheBoundKey() {
    assertThat(atom.key()).isEqualTo(KEY);
  }

  @Test
  void getReturnsCurrentValue() {
    Snapshot<String> snapshot = atom.get();

    assertThat(snapshot.value()).isEqualTo("initial");
    assertThat(snapshot.token()).isNotNull();
  }

  @Test
  void getThrowsOnDeadAtom() {
    atom.delete();

    assertThatThrownBy(atom::get).isInstanceOf(AtomExpiredException.class);
  }

  @Test
  void setUpdatesValueAndToken() {
    String originalToken = atom.get().token();

    atom.set("updated", TTL);

    Snapshot<String> snapshot = atom.get();
    assertThat(snapshot.value()).isEqualTo("updated");
    assertThat(snapshot.token()).isNotEqualTo(originalToken);
  }

  @Test
  void setThrowsOnDeadAtom() {
    atom.delete();

    assertThatThrownBy(() -> atom.set("value", TTL)).isInstanceOf(AtomExpiredException.class);
  }

  @Test
  void touchDoesNotBumpToken() {
    String originalToken = atom.get().token();

    boolean result = atom.touch(TTL);

    assertThat(result).isTrue();
    assertThat(atom.get().token()).isEqualTo(originalToken);
  }

  @Test
  void touchReturnsFalseOnDeadAtom() {
    atom.delete();

    assertThat(atom.touch(TTL)).isFalse();
  }

  // ═══════════════ blocking subscribe tests ═══════════════

  @Test
  void subscribeDeliversCurrentSnapshotAsFirstResult() {
    BlockingSubscription<Snapshot<String>> sub = atom.subscribe();
    try {
      NextResult<Snapshot<String>> result = sub.next(Duration.ofSeconds(5));

      assertThat(result).isInstanceOf(NextResult.Value.class);
      NextResult.Value<Snapshot<String>> value = (NextResult.Value<Snapshot<String>>) result;
      assertThat(value.value().value()).isEqualTo("initial");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeWithStaleLastSeenDeliversCurrentImmediately() {
    Snapshot<String> staleSnapshot = new Snapshot<>("old", "stale-token");

    BlockingSubscription<Snapshot<String>> sub = atom.subscribe(staleSnapshot);
    try {
      NextResult<Snapshot<String>> result = sub.next(Duration.ofSeconds(5));

      assertThat(result).isInstanceOf(NextResult.Value.class);
      NextResult.Value<Snapshot<String>> value = (NextResult.Value<Snapshot<String>>) result;
      assertThat(value.value().value()).isEqualTo("initial");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeWithCurrentTokenBlocksUntilSet() throws InterruptedException {
    Snapshot<String> current = atom.get();

    BlockingSubscription<Snapshot<String>> sub = atom.subscribe(current);
    try {
      CountDownLatch threadStarted = new CountDownLatch(1);
      AtomicReference<NextResult<Snapshot<String>>> result = new AtomicReference<>();
      Thread.ofVirtual()
          .start(
              () -> {
                threadStarted.countDown();
                result.set(sub.next(Duration.ofSeconds(5)));
              });

      assertThat(threadStarted.await(5, TimeUnit.SECONDS)).isTrue();

      atom.set("changed", TTL);

      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                assertThat(result.get()).isNotNull();
                assertThat(result.get()).isInstanceOf(NextResult.Value.class);
                NextResult.Value<Snapshot<String>> value =
                    (NextResult.Value<Snapshot<String>>) result.get();
                assertThat(value.value().value()).isEqualTo("changed");
              });
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeReturnsTimeoutWhenNothingChanges() {
    Snapshot<String> current = atom.get();

    BlockingSubscription<Snapshot<String>> sub = atom.subscribe(current);
    try {
      NextResult<Snapshot<String>> result = sub.next(Duration.ofMillis(200));

      assertThat(result).isInstanceOf(NextResult.Timeout.class);
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeDeliversDeletedOnAtomDeletion() throws InterruptedException {
    Snapshot<String> current = atom.get();

    BlockingSubscription<Snapshot<String>> sub = atom.subscribe(current);
    try {
      CountDownLatch threadStarted = new CountDownLatch(1);
      AtomicReference<NextResult<Snapshot<String>>> result = new AtomicReference<>();
      Thread.ofVirtual()
          .start(
              () -> {
                threadStarted.countDown();
                result.set(sub.next(Duration.ofSeconds(5)));
              });

      assertThat(threadStarted.await(5, TimeUnit.SECONDS)).isTrue();

      atom.delete();

      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(result.get()).isInstanceOf(NextResult.Deleted.class));
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeDeliversExpiredWhenAtomTtlElapses() {
    InMemoryAtomSpi shortSpi = new InMemoryAtomSpi();

    byte[] bytes = STRING_CODEC.encode("ephemeral");
    String token = DefaultAtom.token(bytes);
    Duration shortTtl = Duration.ofMillis(200);
    shortSpi.create(KEY, bytes, token, shortTtl);

    DefaultAtom<String> shortAtom = new DefaultAtom<>(context(shortSpi), KEY, STRING_CODEC, false);

    Snapshot<String> current = shortAtom.get();

    BlockingSubscription<Snapshot<String>> sub = shortAtom.subscribe(current);
    try {
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                NextResult<Snapshot<String>> result = sub.next(Duration.ofMillis(100));
                assertThat(result).isInstanceOf(NextResult.Expired.class);
              });
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeCoalescesRapidSets() {
    Snapshot<String> current = atom.get();

    BlockingSubscription<Snapshot<String>> sub = atom.subscribe(current);
    try {
      atom.set("v1", TTL);
      atom.set("v2", TTL);
      atom.set("v3", TTL);

      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                NextResult<Snapshot<String>> result = sub.next(Duration.ofSeconds(2));
                assertThat(result).isInstanceOf(NextResult.Value.class);
                NextResult.Value<Snapshot<String>> value =
                    (NextResult.Value<Snapshot<String>>) result;
                assertThat(value.value().value()).isEqualTo("v3");
              });
    } finally {
      sub.cancel();
    }
  }

  // ═══════════════ callback subscribe tests ═══════════════

  @Test
  void callbackSubscribeFiresOnNextForNewSnapshot() {
    Snapshot<String> current = atom.get();
    AtomicReference<Snapshot<String>> captured = new AtomicReference<>();
    CountDownLatch invoked = new CountDownLatch(1);

    Subscription sub =
        atom.subscribe(
            current,
            (Snapshot<String> snap) -> {
              captured.set(snap);
              invoked.countDown();
            });
    try {
      atom.set("callback-value", TTL);

      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                assertThat(invoked.await(0, TimeUnit.MILLISECONDS)).isTrue();
                assertThat(captured.get().value()).isEqualTo("callback-value");
              });
    } finally {
      sub.cancel();
    }
  }

  @Test
  void callbackSubscribeFiresOnDeleteWhenAtomDeleted() {
    Snapshot<String> current = atom.get();
    AtomicBoolean deleteFired = new AtomicBoolean(false);

    Subscription sub =
        atom.subscribe(
            current,
            (SubscriberConfig<Snapshot<String>> cfg) ->
                cfg.onNext(snap -> {}).onDeleted(() -> deleteFired.set(true)));
    try {
      await().atMost(Duration.ofSeconds(2)).until(sub::isActive);
      atom.delete();

      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(deleteFired.get()).isTrue());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void callbackSubscribeFiresOnExpirationWhenAtomExpires() {
    InMemoryAtomSpi shortSpi = new InMemoryAtomSpi();

    byte[] bytes = STRING_CODEC.encode("ephemeral");
    String token = DefaultAtom.token(bytes);
    Duration shortTtl = Duration.ofMillis(200);
    shortSpi.create(KEY, bytes, token, shortTtl);

    DefaultAtom<String> shortAtom = new DefaultAtom<>(context(shortSpi), KEY, STRING_CODEC, false);

    Snapshot<String> current = shortAtom.get();
    AtomicBoolean expirationFired = new AtomicBoolean(false);

    Subscription sub =
        shortAtom.subscribe(
            current,
            (SubscriberConfig<Snapshot<String>> cfg) ->
                cfg.onNext(snap -> {}).onExpired(() -> expirationFired.set(true)));
    try {
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(expirationFired.get()).isTrue());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void callbackSubscribeNeverFiresOnComplete() {
    AtomicBoolean completeFired = new AtomicBoolean(false);

    Subscription sub =
        atom.subscribe(
            (SubscriberConfig<Snapshot<String>> cfg) ->
                cfg.onNext(snap -> {}).onCompleted(() -> completeFired.set(true)));
    try {
      await()
          .pollDelay(Duration.ofMillis(500))
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(completeFired.get()).isFalse());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void callbackSubscribeFromCurrentStateDeliversInitialSnapshot() {
    AtomicReference<Snapshot<String>> captured = new AtomicReference<>();
    CountDownLatch invoked = new CountDownLatch(1);

    Subscription sub =
        atom.subscribe(
            (Snapshot<String> snap) -> {
              captured.set(snap);
              invoked.countDown();
            });
    try {
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                assertThat(invoked.await(0, TimeUnit.MILLISECONDS)).isTrue();
                assertThat(captured.get().value()).isEqualTo("initial");
              });
    } finally {
      sub.cancel();
    }
  }

  // ═══════════════ token & TTL tests ═══════════════

  @Test
  void tokenIsContentDerived() throws Exception {
    byte[] bytes = "hello".getBytes(UTF_8);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    String expected =
        Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));

    assertThat(DefaultAtom.token(bytes)).isEqualTo(expected);
    assertThat(expected).hasSize(22);
  }

  @Test
  void identicalEncodedBytesProduceIdenticalTokens() {
    byte[] bytes1 = "same-value".getBytes(UTF_8);
    byte[] bytes2 = "same-value".getBytes(UTF_8);

    assertThat(DefaultAtom.token(bytes1)).isEqualTo(DefaultAtom.token(bytes2));
  }

  @Test
  void setWithSameValueProducesSameToken() {
    Snapshot<String> before = atom.get();

    atom.set("initial", TTL);

    Snapshot<String> after = atom.get();
    assertThat(after.token()).isEqualTo(before.token());
  }

  @Test
  void setThrowsWhenTtlExceedsMaxTtl() {
    Duration excessive = Duration.ofHours(25);

    assertThatThrownBy(() -> atom.set("value", excessive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds configured maximum");
  }

  @Test
  void touchThrowsWhenTtlExceedsMaxTtl() {
    Duration excessive = Duration.ofHours(25);

    assertThatThrownBy(() -> atom.touch(excessive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds configured maximum");
  }

  private DefaultAtom<String> connectedAtom(AtomSpi mockSpi) {
    return new DefaultAtom<>(context(mockSpi), KEY, STRING_CODEC, true);
  }

  @Test
  void connectSourcedGetOnNonexistentThrowsAtomNotFoundException() {
    AtomSpi mockSpi = org.mockito.Mockito.mock(AtomSpi.class);
    org.mockito.Mockito.when(mockSpi.exists(KEY)).thenReturn(false);
    DefaultAtom<String> a = connectedAtom(mockSpi);
    assertThatThrownBy(a::get)
        .isInstanceOf(org.jwcarman.substrate.atom.AtomNotFoundException.class);
  }

  @Test
  void connectSourcedSetOnNonexistentThrows() {
    AtomSpi mockSpi = org.mockito.Mockito.mock(AtomSpi.class);
    org.mockito.Mockito.when(mockSpi.exists(KEY)).thenReturn(false);
    DefaultAtom<String> a = connectedAtom(mockSpi);
    assertThatThrownBy(() -> a.set("x", Duration.ofMinutes(1)))
        .isInstanceOf(org.jwcarman.substrate.atom.AtomNotFoundException.class);
  }

  @Test
  void createSourcedHandleDoesNotProbe() {
    AtomSpi mockSpi = org.mockito.Mockito.mock(AtomSpi.class);
    DefaultAtom<String> a = new DefaultAtom<>(context(mockSpi), KEY, STRING_CODEC, false);
    org.mockito.Mockito.when(mockSpi.read(KEY))
        .thenReturn(java.util.Optional.of(new RawAtom("v".getBytes(UTF_8), "tok")));
    a.get();
    org.mockito.Mockito.verify(mockSpi, org.mockito.Mockito.never())
        .exists(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void probeFiresExactlyOnceAcrossManyOperations() {
    AtomSpi mockSpi = org.mockito.Mockito.mock(AtomSpi.class);
    org.mockito.Mockito.when(mockSpi.exists(KEY)).thenReturn(true);
    org.mockito.Mockito.when(mockSpi.read(KEY))
        .thenReturn(java.util.Optional.of(new RawAtom("v".getBytes(UTF_8), "tok")));
    org.mockito.Mockito.when(
            mockSpi.set(
                org.mockito.ArgumentMatchers.eq(KEY),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);
    DefaultAtom<String> a = connectedAtom(mockSpi);
    a.get();
    a.set("x", Duration.ofMinutes(1));
    a.get();
    org.mockito.Mockito.verify(mockSpi, org.mockito.Mockito.times(1)).exists(KEY);
  }

  @Test
  void connectSourcedDeleteDoesNotProbe() {
    AtomSpi mockSpi = org.mockito.Mockito.mock(AtomSpi.class);
    DefaultAtom<String> a = connectedAtom(mockSpi);
    a.delete();
    org.mockito.Mockito.verify(mockSpi, org.mockito.Mockito.never())
        .exists(org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(mockSpi).delete(KEY);
  }
}
