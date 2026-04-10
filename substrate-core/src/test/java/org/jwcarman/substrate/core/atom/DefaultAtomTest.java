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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.atom.Snapshot;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.memory.InMemoryNotifier;

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

    atom = new DefaultAtom<>(spi, KEY, STRING_CODEC, notifier);
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
  void deleteRemovesAtom() {
    atom.delete();

    assertThatThrownBy(atom::get).isInstanceOf(AtomExpiredException.class);
  }

  @Test
  void watchReturnsImmediatelyWhenTokenDiffers() {
    Snapshot<String> staleSnapshot = new Snapshot<>("old", "stale-token");

    Optional<Snapshot<String>> result = atom.watch(staleSnapshot, Duration.ofSeconds(1));

    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo("initial");
  }

  @Test
  void watchWithNullLastSeenReturnsImmediately() {
    Optional<Snapshot<String>> result = atom.watch(null, Duration.ofSeconds(1));

    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo("initial");
  }

  @Test
  void watchBlocksUntilSetAndReturnsNewSnapshot() {
    Snapshot<String> current = atom.get();
    AtomicReference<Optional<Snapshot<String>>> result = new AtomicReference<>();

    Thread.ofVirtual().start(() -> result.set(atom.watch(current, Duration.ofSeconds(5))));

    await().pollDelay(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1)).until(() -> true);

    atom.set("changed", TTL);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(result.get()).isNotNull();
              assertThat(result.get()).isPresent();
              assertThat(result.get().get().value()).isEqualTo("changed");
            });
  }

  @Test
  void watchReturnsEmptyOnTimeout() {
    Snapshot<String> current = atom.get();

    Optional<Snapshot<String>> result = atom.watch(current, Duration.ofMillis(100));

    assertThat(result).isEmpty();
  }

  @Test
  void watchThrowsOnDeadAtomAtCallTime() {
    Snapshot<String> current = atom.get();
    atom.delete();

    assertThatThrownBy(() -> atom.watch(current, Duration.ofSeconds(1)))
        .isInstanceOf(AtomExpiredException.class);
  }

  @Test
  void watchThrowsWhenAtomDiesDuringWait() {
    Snapshot<String> current = atom.get();
    AtomicReference<Throwable> caught = new AtomicReference<>();

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                atom.watch(current, Duration.ofSeconds(5));
              } catch (AtomExpiredException e) {
                caught.set(e);
              }
            });

    await().pollDelay(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1)).until(() -> true);

    atom.delete();
    notifier.notify(KEY, "deleted");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(caught.get()).isInstanceOf(AtomExpiredException.class));
  }

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
}
