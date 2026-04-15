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
package org.jwcarman.substrate.nats.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.jwcarman.substrate.nats.AbstractNatsIT;

class NatsAtomIT extends AbstractNatsIT {

  private NatsAtomSpi atom;

  @BeforeEach
  void setUp() {
    atom =
        new NatsAtomSpi(
            connection,
            "substrate:atom:",
            "substrate-atoms-" + System.nanoTime(),
            Duration.ofMinutes(5));
  }

  @Test
  void createThenReadReturnsValue() {
    String key = atom.atomKey("test-" + System.nanoTime());
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);

    atom.create(key, value, "tok1", Duration.ofMinutes(5));

    Optional<RawAtom> result = atom.read(key);
    assertThat(result).isPresent();
    assertThat(result.get().token()).isEqualTo("tok1");
    assertThat(result.get().value()).isEqualTo(value);
  }

  @Test
  void createThrowsOnDuplicate() {
    String key = atom.atomKey("dup-" + System.nanoTime());
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);

    Duration ttl = Duration.ofMinutes(5);
    atom.create(key, value, "tok1", ttl);

    assertThatThrownBy(() -> atom.create(key, value, "tok2", ttl))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void readReturnsEmptyWhenAbsent() {
    String key = atom.atomKey("absent-" + System.nanoTime());
    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void setUpdatesValue() {
    String key = atom.atomKey("set-" + System.nanoTime());
    atom.create(key, "old".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    boolean result =
        atom.set(key, "new".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5));

    assertThat(result).isTrue();
    Optional<RawAtom> read = atom.read(key);
    assertThat(read).isPresent();
    assertThat(read.get().token()).isEqualTo("tok2");
    assertThat(new String(read.get().value(), StandardCharsets.UTF_8)).isEqualTo("new");
  }

  @Test
  void setReturnsFalseWhenAbsent() {
    String key = atom.atomKey("absent-" + System.nanoTime());
    assertThat(atom.set(key, "new".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5)))
        .isFalse();
  }

  @Test
  void touchReturnsTrueWhenPresent() {
    String key = atom.atomKey("touch-" + System.nanoTime());
    atom.create(key, "hello".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    assertThat(atom.touch(key, Duration.ofMinutes(10))).isTrue();

    Optional<RawAtom> result = atom.read(key);
    assertThat(result).isPresent();
    assertThat(result.get().token()).isEqualTo("tok1");
  }

  @Test
  void touchReturnsFalseWhenAbsent() {
    String key = atom.atomKey("absent-" + System.nanoTime());
    assertThat(atom.touch(key, Duration.ofMinutes(10))).isFalse();
  }

  @Test
  void deleteRemovesAtom() {
    String key = atom.atomKey("delete-" + System.nanoTime());
    atom.create(key, "hello".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    atom.delete(key);

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void setReturnsFalseAfterDelete() {
    String key = atom.atomKey("deleted-set-" + System.nanoTime());
    atom.create(key, "hello".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));
    atom.delete(key);

    assertThat(atom.set(key, "new".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5)))
        .isFalse();
  }

  @Test
  void touchReturnsFalseAfterDelete() {
    String key = atom.atomKey("deleted-touch-" + System.nanoTime());
    atom.create(key, "hello".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));
    atom.delete(key);

    assertThat(atom.touch(key, Duration.ofMinutes(10))).isFalse();
  }

  @Test
  void existsReturnsFalseForNeverCreatedKey() {
    assertThat(atom.exists(atom.atomKey("never-" + System.nanoTime()))).isFalse();
  }

  @Test
  void existsReturnsTrueAfterCreate() {
    String key = atom.atomKey("exists-" + System.nanoTime());
    atom.create(key, "v".getBytes(StandardCharsets.UTF_8), "tok", Duration.ofMinutes(5));
    assertThat(atom.exists(key)).isTrue();
  }

  @Test
  void existsReturnsFalseAfterDelete() {
    String key = atom.atomKey("exists-del-" + System.nanoTime());
    atom.create(key, "v".getBytes(StandardCharsets.UTF_8), "tok", Duration.ofMinutes(5));
    atom.delete(key);
    assertThat(atom.exists(key)).isFalse();
  }

  @Test
  void concurrentCreateExactlyOneSucceeds() {
    String key = atom.atomKey("concurrent-" + System.nanoTime());
    int threadCount = 8;
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    IntStream.range(0, threadCount)
        .mapToObj(
            i ->
                Thread.ofVirtual()
                    .start(
                        () -> {
                          try {
                            atom.create(
                                key,
                                ("payload-" + i).getBytes(StandardCharsets.UTF_8),
                                "tok-" + i,
                                Duration.ofMinutes(5));
                            successes.incrementAndGet();
                          } catch (AtomAlreadyExistsException _) {
                            failures.incrementAndGet();
                          }
                        }))
        .toList()
        .forEach(
            t -> {
              try {
                t.join();
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            });

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threadCount - 1);
    assertThat(atom.read(key)).isPresent();
  }

  @Test
  void atomExpiresViaBucketTtl() {
    NatsAtomSpi shortTtlAtom =
        new NatsAtomSpi(
            connection,
            "substrate:atom:",
            "substrate-atoms-ttl-" + System.nanoTime(),
            Duration.ofSeconds(2));

    String key = shortTtlAtom.atomKey("expiring-" + System.nanoTime());
    shortTtlAtom.create(
        key, "ephemeral".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofSeconds(2));

    assertThat(shortTtlAtom.read(key)).isPresent();

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(shortTtlAtom.read(key)).isEmpty());
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(atom.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void sweepReturnsZero() {
    assertThat(atom.sweep(100)).isZero();
  }
}
