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
package org.jwcarman.substrate.hazelcast.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.CasResult;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.jwcarman.substrate.hazelcast.AbstractHazelcastIT;

class HazelcastAtomIT extends AbstractHazelcastIT {

  private HazelcastAtomSpi spi;

  @BeforeEach
  void setUp() {
    spi =
        new HazelcastAtomSpi(hazelcast, "substrate:atom:", "substrate-atoms-" + System.nanoTime());
  }

  @Test
  void existsReturnsFalseForNeverCreatedKey() {
    assertThat(spi.exists(spi.atomKey("never-" + System.nanoTime()))).isFalse();
  }

  @Test
  void existsReturnsTrueAfterCreate() {
    String key = spi.atomKey("exists-" + System.nanoTime());
    spi.create(key, "v".getBytes(StandardCharsets.UTF_8), "tok", Duration.ofMinutes(5));
    assertThat(spi.exists(key)).isTrue();
  }

  @Test
  void createAndReadReturnsValue() {
    String key = spi.atomKey("test-" + System.nanoTime());
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);

    spi.create(key, value, "token-1", Duration.ofMinutes(5));

    Optional<RawAtom> result = spi.read(key);
    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo(value);
    assertThat(result.get().token()).isEqualTo("token-1");
  }

  @Test
  void createThrowsWhenKeyAlreadyExists() {
    String key = spi.atomKey("dup-" + System.nanoTime());
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);

    spi.create(key, value, "token-1", Duration.ofMinutes(5));

    byte[] other = "other".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofMinutes(5);
    assertThrows(AtomAlreadyExistsException.class, () -> spi.create(key, other, "token-2", ttl));
  }

  @Test
  void readReturnsEmptyForAbsentKey() {
    String key = spi.atomKey("absent-" + System.nanoTime());

    assertThat(spi.read(key)).isEmpty();
  }

  @Test
  void setUpdatesExistingAtom() {
    String key = spi.atomKey("set-" + System.nanoTime());
    spi.create(key, "original".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMinutes(5));

    boolean result =
        spi.set(key, "updated".getBytes(StandardCharsets.UTF_8), "token-2", Duration.ofMinutes(5));

    assertThat(result).isTrue();
    Optional<RawAtom> atom = spi.read(key);
    assertThat(atom).isPresent();
    assertThat(new String(atom.get().value(), StandardCharsets.UTF_8)).isEqualTo("updated");
    assertThat(atom.get().token()).isEqualTo("token-2");
  }

  @Test
  void setReturnsFalseForAbsentKey() {
    String key = spi.atomKey("no-set-" + System.nanoTime());

    boolean result =
        spi.set(key, "value".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMinutes(5));

    assertThat(result).isFalse();
  }

  @Test
  void touchExtendsTtl() {
    String key = spi.atomKey("touch-" + System.nanoTime());
    spi.create(key, "value".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofSeconds(2));

    boolean result = spi.touch(key, Duration.ofMinutes(5));

    assertThat(result).isTrue();
    assertThat(spi.read(key)).isPresent();
  }

  @Test
  void touchReturnsFalseForAbsentKey() {
    String key = spi.atomKey("no-touch-" + System.nanoTime());

    assertThat(spi.touch(key, Duration.ofMinutes(5))).isFalse();
  }

  @Test
  void deleteRemovesAtom() {
    String key = spi.atomKey("delete-" + System.nanoTime());
    spi.create(key, "value".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMinutes(5));

    spi.delete(key);

    assertThat(spi.read(key)).isEmpty();
  }

  @Test
  void deleteIsIdempotent() {
    String key = spi.atomKey("idempotent-" + System.nanoTime());

    assertThatNoException()
        .isThrownBy(
            () -> {
              spi.delete(key);
              spi.delete(key);
            });
  }

  @Test
  void ttlExpiresEntry() {
    String key = spi.atomKey("ttl-" + System.nanoTime());
    spi.create(key, "ephemeral".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofSeconds(1));

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(spi.read(key)).isEmpty());
  }

  @Test
  void concurrentCreateExactlyOneSucceeds() {
    String key = spi.atomKey("concurrent-" + System.nanoTime());
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
                            spi.create(
                                key,
                                ("payload-" + i).getBytes(StandardCharsets.UTF_8),
                                "token-" + i,
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
    assertThat(spi.read(key)).isPresent();
  }

  @Test
  void sweepReturnsZero() {
    assertThat(spi.sweep(100)).isZero();
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(spi.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void setReturnsFalseAfterTtlExpiry() {
    String key = spi.atomKey("set-expired-" + System.nanoTime());
    spi.create(key, "value".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofSeconds(1));

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(spi.read(key)).isEmpty());

    assertThat(
            spi.set(key, "new".getBytes(StandardCharsets.UTF_8), "token-2", Duration.ofMinutes(5)))
        .isFalse();
  }

  @Test
  void touchRenewalKeepsAtomAlive() {
    String key = spi.atomKey("renew-" + System.nanoTime());
    spi.create(key, "value".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofSeconds(2));

    spi.touch(key, Duration.ofMinutes(5));

    await()
        .pollDelay(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(spi.read(key)).isPresent());
  }

  @Test
  void compareAndSetCommitsWhenTokenMatches() {
    String key = spi.atomKey("cas-match-" + System.nanoTime());
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));

    CasResult result =
        spi.compareAndSet(
            key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.COMMITTED);
    assertThat(spi.read(key)).hasValueSatisfying(raw -> assertThat(raw.token()).isEqualTo("tok-2"));
  }

  @Test
  void compareAndSetReportsMismatchAndLeavesValueUntouched() {
    String key = spi.atomKey("cas-mismatch-" + System.nanoTime());
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
    String key = spi.atomKey("cas-deleted-" + System.nanoTime());
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    spi.delete(key);

    assertThat(
            spi.compareAndSet(
                key,
                "tok-1",
                "v2".getBytes(StandardCharsets.UTF_8),
                "tok-2",
                Duration.ofMinutes(5)))
        .isEqualTo(CasResult.ABSENT);
  }

  @Test
  void compareAndSetReportsAbsentForNeverCreatedAtom() {
    String key = spi.atomKey("cas-missing-" + System.nanoTime());

    assertThat(
            spi.compareAndSet(
                key,
                "tok-1",
                "v2".getBytes(StandardCharsets.UTF_8),
                "tok-2",
                Duration.ofMinutes(5)))
        .isEqualTo(CasResult.ABSENT);
  }

  @Test
  void setNeverLeavesTheAtomWithoutAnExpiration() {
    String mapName = "substrate-atoms-ttl-" + System.nanoTime();
    HazelcastAtomSpi ttlSpi = new HazelcastAtomSpi(hazelcast, "substrate:atom:", mapName);
    String key = ttlSpi.atomKey("ttl-guard");

    ttlSpi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    ttlSpi.set(key, "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    long expiration =
        hazelcast.<String, AtomEntry>getMap(mapName).getEntryView(key).getExpirationTime();
    assertThat(expiration).isLessThan(Long.MAX_VALUE);
    assertThat(expiration)
        .isCloseTo(System.currentTimeMillis() + Duration.ofMinutes(5).toMillis(), within(30_000L));
  }

  @Test
  void compareAndSetNeverLeavesTheAtomWithoutAnExpiration() {
    String mapName = "substrate-atoms-ttl-cas-" + System.nanoTime();
    HazelcastAtomSpi ttlSpi = new HazelcastAtomSpi(hazelcast, "substrate:atom:", mapName);
    String key = ttlSpi.atomKey("ttl-guard-cas");

    ttlSpi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    ttlSpi.compareAndSet(
        key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    long expiration =
        hazelcast.<String, AtomEntry>getMap(mapName).getEntryView(key).getExpirationTime();
    assertThat(expiration).isLessThan(Long.MAX_VALUE);
    assertThat(expiration)
        .isCloseTo(System.currentTimeMillis() + Duration.ofMinutes(5).toMillis(), within(30_000L));
  }
}
