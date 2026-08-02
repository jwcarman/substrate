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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.CasResult;
import org.jwcarman.substrate.core.atom.RawAtom;

class InMemoryAtomSpiTest {

  private InMemoryAtomSpi spi;

  @BeforeEach
  void setUp() {
    spi = new InMemoryAtomSpi();
  }

  @Test
  void existsReturnsFalseForNeverCreatedKey() {
    assertThat(spi.exists("missing")).isFalse();
  }

  @Test
  void existsReturnsTrueForCreatedKey() {
    spi.create("key", new byte[] {1}, "t", Duration.ofSeconds(10));
    assertThat(spi.exists("key")).isTrue();
  }

  @Test
  void existsReturnsFalseAfterExpiry() {
    spi.create("key", new byte[] {1}, "t", Duration.ofMillis(1));
    await().atMost(Duration.ofSeconds(2)).until(() -> !spi.exists("key"));
  }

  @Test
  void createStoresAtom() {
    spi.create("key", new byte[] {1, 2, 3}, "token1", Duration.ofSeconds(10));

    Optional<RawAtom> raw = spi.read("key");
    assertThat(raw).isPresent();
    assertThat(raw.get().token()).isEqualTo("token1");
    assertThat(raw.get().value()).containsExactly(1, 2, 3);
  }

  @Test
  void createThrowsOnDuplicateKey() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofSeconds(10));

    Duration tenSeconds = Duration.ofSeconds(10);
    byte[] newValue = new byte[] {2};
    assertThatThrownBy(() -> spi.create("key", newValue, "t2", tenSeconds))
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
    RawAtom raw = spi.read("key").orElseThrow();
    assertThat(raw.value()).containsExactly(2);
    assertThat(raw.token()).isEqualTo("t2");
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
    assertThatNoException().isThrownBy(() -> spi.delete("missing"));
  }

  @Test
  void atomKeyAppliesPrefix() {
    assertThat(spi.atomKey("test")).isEqualTo("substrate:atom:test");
  }

  @Test
  void sweepRemovesExpiredEntries() {
    for (int i = 0; i < 5; i++) {
      spi.create("key-" + i, new byte[] {(byte) i}, "t" + i, Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              int removed = spi.sweep(10);
              assertThat(removed).isEqualTo(5);
            });

    for (int i = 0; i < 5; i++) {
      assertThat(spi.read("key-" + i)).isEmpty();
    }
  }

  @Test
  void sweepRespectsMaxToSweep() {
    for (int i = 0; i < 10; i++) {
      spi.create("key-" + i, new byte[] {(byte) i}, "t" + i, Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(spi.sweep(3)).isEqualTo(3));

    assertThat(spi.sweep(10)).isEqualTo(7);
  }

  @Test
  void sweepReturnsZeroWhenNothingExpired() {
    spi.create("key", new byte[] {1}, "t1", Duration.ofSeconds(10));

    assertThat(spi.sweep(1000)).isZero();
  }

  @Test
  void compareAndSetCommitsWhenTokenMatches() {
    String key = spi.atomKey("cas-match");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));

    CasResult result =
        spi.compareAndSet(
            key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.COMMITTED);
    assertThat(spi.read(key))
        .hasValueSatisfying(
            raw -> {
              assertThat(raw.value()).isEqualTo("v2".getBytes(StandardCharsets.UTF_8));
              assertThat(raw.token()).isEqualTo("tok-2");
            });
  }

  @Test
  void compareAndSetReportsMismatchAndLeavesValueUntouched() {
    String key = spi.atomKey("cas-mismatch");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));

    CasResult result =
        spi.compareAndSet(
            key, "stale", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.TOKEN_MISMATCH);
    assertThat(spi.read(key))
        .hasValueSatisfying(
            raw -> {
              assertThat(raw.value()).isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
              assertThat(raw.token()).isEqualTo("tok-1");
            });
  }

  @Test
  void compareAndSetReportsAbsentForDeletedAtom() {
    String key = spi.atomKey("cas-deleted");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    spi.delete(key);

    CasResult result =
        spi.compareAndSet(
            key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.ABSENT);
  }

  @Test
  void compareAndSetReportsAbsentForExpiredAtom() throws InterruptedException {
    String key = spi.atomKey("cas-expired");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMillis(50));
    Thread.sleep(120);

    CasResult result =
        spi.compareAndSet(
            key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.ABSENT);
  }

  @Test
  void compareAndSetResetsTtl() {
    String key = spi.atomKey("cas-ttl");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMillis(100));

    spi.compareAndSet(
        key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(spi.exists(key)).isTrue();
  }

  @Test
  void concurrentCompareAndSetAdmitsExactlyOneWinner() throws InterruptedException {
    String key = spi.atomKey("cas-race");
    spi.create(key, "v0".getBytes(StandardCharsets.UTF_8), "tok-0", Duration.ofMinutes(5));

    int threads = 16;
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger committed = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      int n = i;
      pool.submit(
          () -> {
            latch.await();
            if (spi.compareAndSet(
                    key,
                    "tok-0",
                    ("v" + n).getBytes(StandardCharsets.UTF_8),
                    "tok-" + (n + 1),
                    Duration.ofMinutes(5))
                == CasResult.COMMITTED) {
              committed.incrementAndGet();
            }
            return null;
          });
    }
    latch.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    assertThat(committed.get()).isEqualTo(1);
  }
}
