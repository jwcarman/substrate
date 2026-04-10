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
package org.jwcarman.substrate.core.memory.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AtomRecord;

class InMemoryAtomSpiTest {

  private InMemoryAtomSpi spi;

  @BeforeEach
  void setUp() {
    spi = new InMemoryAtomSpi();
  }

  @Test
  void createStoresAtom() {
    spi.create("key", new byte[] {1, 2, 3}, "token1", Duration.ofSeconds(10));

    Optional<AtomRecord> record = spi.read("key");
    assertThat(record).isPresent();
    assertThat(record.get().token()).isEqualTo("token1");
    assertThat(record.get().value()).containsExactly(1, 2, 3);
  }

  @Test
  void createThrowsOnDuplicateKey() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofSeconds(10));

    assertThatThrownBy(() -> spi.create("key", new byte[] {2}, "t2", Duration.ofSeconds(10)))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void createSucceedsAfterExpiry() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              spi.create("key", new byte[] {2}, "t2", Duration.ofSeconds(10));
              assertThat(spi.read("key").get().token()).isEqualTo("t2");
            });
  }

  @Test
  void concurrentCreateExactlyOneSucceeds() throws InterruptedException {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      executor.submit(
          () -> {
            try {
              latch.await();
              spi.create(
                  "contested-key",
                  new byte[] {(byte) index},
                  "token-" + index,
                  Duration.ofSeconds(10));
              successes.incrementAndGet();
            } catch (AtomAlreadyExistsException _) {
              failures.incrementAndGet();
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
          });
    }

    latch.countDown();
    executor.shutdown();
    executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threadCount - 1);
  }

  @Test
  void readReturnsEmptyForAbsentKey() {
    assertThat(spi.read("missing")).isEmpty();
  }

  @Test
  void readReturnsEmptyAfterExpiry() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(spi.read("key")).isEmpty());
  }

  @Test
  void setUpdatesValueAndToken() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofSeconds(10));

    boolean result = spi.set("key", new byte[] {2}, "t2", Duration.ofSeconds(10));

    assertThat(result).isTrue();
    AtomRecord record = spi.read("key").orElseThrow();
    assertThat(record.value()).containsExactly(2);
    assertThat(record.token()).isEqualTo("t2");
  }

  @Test
  void setReturnsFalseOnDeadAtom() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(spi.set("key", new byte[] {2}, "t2", Duration.ofSeconds(10))).isFalse());
  }

  @Test
  void setReturnsFalseOnAbsentKey() {
    assertThat(spi.set("missing", new byte[] {1}, "t1", Duration.ofSeconds(10))).isFalse();
  }

  @Test
  void touchExtendsLease() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofMillis(50));

    boolean result = spi.touch("key", Duration.ofSeconds(5));
    assertThat(result).isTrue();

    await()
        .pollDelay(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(spi.read("key")).isPresent());
  }

  @Test
  void touchReturnsFalseOnDeadAtom() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(spi.touch("key", Duration.ofSeconds(5))).isFalse());
  }

  @Test
  void touchReturnsFalseOnAbsentKey() {
    assertThat(spi.touch("missing", Duration.ofSeconds(5))).isFalse();
  }

  @Test
  void touchDoesNotChangeToken() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofSeconds(10));

    spi.touch("key", Duration.ofSeconds(10));

    assertThat(spi.read("key").get().token()).isEqualTo("t1");
  }

  @Test
  void deleteRemovesAtom() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofSeconds(10));

    spi.delete("key");

    assertThat(spi.read("key")).isEmpty();
  }

  @Test
  void deleteIsNoOpForAbsentKey() {
    spi.delete("missing");
  }

  @Test
  void atomKeyAppliesPrefix() {
    assertThat(spi.atomKey("test")).isEqualTo("substrate:atom:test");
  }
}
