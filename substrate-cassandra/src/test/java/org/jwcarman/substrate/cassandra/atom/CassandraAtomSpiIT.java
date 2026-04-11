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
package org.jwcarman.substrate.cassandra.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.cassandra.CassandraTestContainer;

class CassandraAtomSpiIT {

  private static CqlSession session;
  private static CassandraAtomSpi atom;

  @BeforeAll
  static void openSession() {
    session =
        CqlSession.builder()
            .addContactPoint(
                new InetSocketAddress(
                    CassandraTestContainer.INSTANCE.getHost(),
                    CassandraTestContainer.INSTANCE.getMappedPort(9042)))
            .withLocalDatacenter("datacenter1")
            .withKeyspace(CqlIdentifier.fromCql("substrate_test"))
            .build();
    atom = new CassandraAtomSpi(session, "substrate:atom:", "substrate_atoms");
  }

  @AfterAll
  static void closeSession() {
    if (session != null) {
      session.close();
    }
  }

  @BeforeEach
  void truncate() {
    session.execute("TRUNCATE substrate_atoms");
  }

  @Test
  void createAndRead() {
    String key = atom.atomKey("test1");
    atom.create(key, "hello".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    var result = atom.read(key);

    assertThat(result).isPresent();
    assertThat(new String(result.get().value(), StandardCharsets.UTF_8)).isEqualTo("hello");
    assertThat(result.get().token()).isEqualTo("tok1");
  }

  @Test
  void createThrowsOnDuplicate() {
    String key = atom.atomKey("dup");
    atom.create(key, "first".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    assertThatThrownBy(
            () ->
                atom.create(
                    key, "second".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5)))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void concurrentCreateExactlyOneWins() throws InterruptedException {
    String key = atom.atomKey("race");
    int threads = 5;
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        int idx = i;
        executor.submit(
            () -> {
              try {
                latch.await();
                atom.create(
                    key,
                    ("val" + idx).getBytes(StandardCharsets.UTF_8),
                    "tok" + idx,
                    Duration.ofMinutes(5));
                successes.incrementAndGet();
              } catch (AtomAlreadyExistsException e) {
                failures.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }
      latch.countDown();
      executor.shutdown();
      executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threads - 1);
  }

  @Test
  void readReturnsEmptyForAbsentKey() {
    assertThat(atom.read(atom.atomKey("nonexistent"))).isEmpty();
  }

  @Test
  void setUpdatesExistingAtom() {
    String key = atom.atomKey("settest");
    atom.create(key, "original".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    boolean applied =
        atom.set(key, "updated".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5));

    assertThat(applied).isTrue();
    var result = atom.read(key);
    assertThat(result).isPresent();
    assertThat(new String(result.get().value(), StandardCharsets.UTF_8)).isEqualTo("updated");
    assertThat(result.get().token()).isEqualTo("tok2");
  }

  @Test
  void setReturnsFalseForAbsentKey() {
    boolean applied =
        atom.set(
            atom.atomKey("missing"),
            "val".getBytes(StandardCharsets.UTF_8),
            "tok",
            Duration.ofMinutes(5));

    assertThat(applied).isFalse();
  }

  @Test
  void touchExtendsExistingAtom() {
    String key = atom.atomKey("touchtest");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    boolean applied = atom.touch(key, Duration.ofMinutes(10));

    assertThat(applied).isTrue();
    assertThat(atom.read(key)).isPresent();
  }

  @Test
  void touchReturnsFalseForAbsentKey() {
    assertThat(atom.touch(atom.atomKey("missing"), Duration.ofMinutes(5))).isFalse();
  }

  @Test
  void deleteRemovesAtom() {
    String key = atom.atomKey("deltest");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    atom.delete(key);

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void deleteIsIdempotent() {
    String key = atom.atomKey("deltwice");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    atom.delete(key);
    atom.delete(key);

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void ttlExpiryMakesAtomDisappear() {
    String key = atom.atomKey("ttltest");
    atom.create(key, "ephemeral".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofSeconds(2));

    assertThat(atom.read(key)).isPresent();

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(atom.read(key)).isEmpty());
  }

  @Test
  void setReturnsFalseAfterTtlExpiry() {
    String key = atom.atomKey("ttlset");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofSeconds(2));

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(atom.read(key)).isEmpty());

    assertThat(atom.set(key, "new".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5)))
        .isFalse();
  }

  @Test
  void touchReturnsFalseAfterTtlExpiry() {
    String key = atom.atomKey("ttltouch");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofSeconds(2));

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(atom.read(key)).isEmpty());

    assertThat(atom.touch(key, Duration.ofMinutes(5))).isFalse();
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(atom.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void sweepReturnsZero() {
    assertThat(atom.sweep(100)).isZero();
  }

  @Test
  void schemaAutoCreationHandlesExistingTable() {
    atom.createSchema();
    atom.createSchema();
  }
}
