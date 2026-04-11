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
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.SubscriberConfig;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.atom.Snapshot;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;

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

  private InMemoryAtomSpi spi;
  private InMemoryNotifier notifier;
  private DefaultAtom<String> atom;

  @BeforeEach
  void setUp() {
    spi = new InMemoryAtomSpi();
    notifier = new InMemoryNotifier();

    byte[] bytes = STRING_CODEC.encode("initial");
    String token = DefaultAtom.token(bytes);
    spi.create(KEY, bytes, token, TTL);

    atom = new DefaultAtom<>(spi, KEY, STRING_CODEC, notifier, Duration.ofHours(24));
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
  void setPublishesNotification() {
    AtomicBoolean notified = new AtomicBoolean(false);
    AtomicReference<String> notifiedPayload = new AtomicReference<>();
    notifier.subscribe(
        (key, payload) -> {
          if (KEY.equals(key)) {
            notified.set(true);
            notifiedPayload.set(payload);
          }
        });

    atom.set("updated", TTL);

    assertThat(notified.get()).isTrue();
    assertThat(notifiedPayload.get()).isEqualTo(atom.get().token());
  }

  @Test
  void setThrowsOnDeadAtom() {
    atom.delete();

    assertThatThrownBy(() -> atom.set("value", TTL)).isInstanceOf(AtomExpiredException.class);
  }

  @Test
  void touchDoesNotBumpTokenOrPublishNotification() {
    String originalToken = atom.get().token();
    AtomicBoolean notified = new AtomicBoolean(false);
    notifier.subscribe(
        (key, payload) -> {
          if (KEY.equals(key)) {
            notified.set(true);
          }
        });

    boolean result = atom.touch(TTL);

    assertThat(result).isTrue();
    assertThat(atom.get().token()).isEqualTo(originalToken);
    assertThat(notified.get()).isFalse();
  }

  @Test
  void touchReturnsFalseOnDeadAtom() {
    atom.delete();

    assertThat(atom.touch(TTL)).isFalse();
  }

  @Test
  void deletePublishesDeletedNotification() {
    AtomicReference<String> notifiedPayload = new AtomicReference<>();
    notifier.subscribe(
        (key, payload) -> {
          if (KEY.equals(key)) {
            notifiedPayload.set(payload);
          }
        });

    atom.delete();

    assertThat(notifiedPayload.get()).isEqualTo("__DELETED__");
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
    InMemoryNotifier shortNotifier = new InMemoryNotifier();

    byte[] bytes = STRING_CODEC.encode("ephemeral");
    String token = DefaultAtom.token(bytes);
    Duration shortTtl = Duration.ofMillis(200);
    shortSpi.create(KEY, bytes, token, shortTtl);

    DefaultAtom<String> shortAtom =
        new DefaultAtom<>(shortSpi, KEY, STRING_CODEC, shortNotifier, Duration.ofHours(24));

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
    InMemoryNotifier shortNotifier = new InMemoryNotifier();

    byte[] bytes = STRING_CODEC.encode("ephemeral");
    String token = DefaultAtom.token(bytes);
    Duration shortTtl = Duration.ofMillis(200);
    shortSpi.create(KEY, bytes, token, shortTtl);

    DefaultAtom<String> shortAtom =
        new DefaultAtom<>(shortSpi, KEY, STRING_CODEC, shortNotifier, Duration.ofHours(24));

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
    String expected =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));

    assertThat(DefaultAtom.token(bytes)).isEqualTo(expected);
    assertThat(expected).hasSize(43);
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
}
