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
package org.jwcarman.substrate.postgresql.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.jwcarman.substrate.postgresql.PostgresTestContainer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class PostgresAtomIT {

  private DataSource dataSource;
  private PostgresAtomSpi atom;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    dataSource = createDataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);

    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    populator.addScript(new ClassPathResource("db/substrate/postgresql/V1__create_atom.sql"));
    populator.execute(dataSource);

    jdbcTemplate.update("DELETE FROM substrate_atom");

    atom = new PostgresAtomSpi(jdbcTemplate, "substrate:atom:");
  }

  @Test
  void createAndReadReturnsValue() {
    String key = atom.atomKey("create-read");
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
    atom.create(key, value, "token-1", Duration.ofMinutes(5));

    Optional<RawAtom> result = atom.read(key);

    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo(value);
    assertThat(result.get().token()).isEqualTo("token-1");
  }

  @Test
  void createThrowsWhenKeyAlreadyExists() {
    String key = atom.atomKey("duplicate");
    byte[] value = "first".getBytes(StandardCharsets.UTF_8);
    atom.create(key, value, "token-1", Duration.ofMinutes(5));

    byte[] second = "second".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofMinutes(5);
    assertThrows(AtomAlreadyExistsException.class, () -> atom.create(key, second, "token-2", ttl));
  }

  @Test
  void readReturnsEmptyForNonexistentKey() {
    Optional<RawAtom> result = atom.read(atom.atomKey("nonexistent"));

    assertThat(result).isEmpty();
  }

  @Test
  void readReturnsEmptyForExpiredKey() {
    String key = atom.atomKey("expired-read");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMillis(50));

    await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(atom.read(key)).isEmpty());
  }

  @Test
  void setUpdatesValueAndToken() {
    String key = atom.atomKey("set-test");
    atom.create(key, "original".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMinutes(5));

    boolean updated =
        atom.set(key, "updated".getBytes(StandardCharsets.UTF_8), "token-2", Duration.ofMinutes(5));

    assertThat(updated).isTrue();
    Optional<RawAtom> result = atom.read(key);
    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo("updated".getBytes(StandardCharsets.UTF_8));
    assertThat(result.get().token()).isEqualTo("token-2");
  }

  @Test
  void setReturnsFalseForNonexistentKey() {
    boolean updated =
        atom.set(
            atom.atomKey("no-such-key"),
            "value".getBytes(StandardCharsets.UTF_8),
            "token-1",
            Duration.ofMinutes(5));

    assertThat(updated).isFalse();
  }

  @Test
  void setReturnsFalseForExpiredKey() {
    String key = atom.atomKey("expired-set");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              boolean updated =
                  atom.set(
                      key,
                      "new".getBytes(StandardCharsets.UTF_8),
                      "token-2",
                      Duration.ofMinutes(5));
              assertThat(updated).isFalse();
            });
  }

  @Test
  void touchExtendsExpiry() {
    String key = atom.atomKey("touch-test");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMillis(200));

    boolean touched = atom.touch(key, Duration.ofMinutes(5));

    assertThat(touched).isTrue();
    assertThat(atom.read(key)).isPresent();
  }

  @Test
  void touchReturnsFalseForNonexistentKey() {
    boolean touched = atom.touch(atom.atomKey("no-such-key"), Duration.ofMinutes(5));

    assertThat(touched).isFalse();
  }

  @Test
  void touchReturnsFalseForExpiredKey() {
    String key = atom.atomKey("expired-touch");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              boolean touched = atom.touch(key, Duration.ofMinutes(5));
              assertThat(touched).isFalse();
            });
  }

  @Test
  void deleteRemovesAtom() {
    String key = atom.atomKey("delete-test");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMinutes(5));

    atom.delete(key);

    assertThat(atom.read(key)).isEmpty();
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_atom WHERE key = ?", Integer.class, key);
    assertThat(count).isZero();
  }

  @Test
  void deleteIsIdempotent() {
    String key = atom.atomKey("delete-twice");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMinutes(5));

    atom.delete(key);
    atom.delete(key);

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void setAfterDeleteReturnsFalse() {
    String key = atom.atomKey("set-after-delete");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "token-1", Duration.ofMinutes(5));
    atom.delete(key);

    boolean updated =
        atom.set(key, "new".getBytes(StandardCharsets.UTF_8), "token-2", Duration.ofMinutes(5));

    assertThat(updated).isFalse();
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(atom.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void concurrentCreateCollision() {
    String key = atom.atomKey("concurrent-create");
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
    assertThat(atom.read(key)).isPresent();
  }

  @Test
  void sweepRemovesExpiredAtoms() {
    for (int i = 0; i < 10; i++) {
      atom.create(
          atom.atomKey("sweep-" + i),
          "data".getBytes(StandardCharsets.UTF_8),
          "token",
          Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              int swept = atom.sweep(100);
              assertThat(swept).isEqualTo(10);
            });

    Integer count =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM substrate_atom", Integer.class);
    assertThat(count).isZero();
  }

  @Test
  void sweepRespectsLimit() {
    for (int i = 0; i < 10; i++) {
      atom.create(
          atom.atomKey("sweep-limit-" + i),
          "data".getBytes(StandardCharsets.UTF_8),
          "token",
          Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              int swept = atom.sweep(5);
              assertThat(swept).isEqualTo(5);
            });
  }

  @Test
  void sweepDoesNotRemoveLiveAtoms() {
    atom.create(
        atom.atomKey("live"),
        "data".getBytes(StandardCharsets.UTF_8),
        "token",
        Duration.ofMinutes(5));
    atom.create(
        atom.atomKey("expired"),
        "data".getBytes(StandardCharsets.UTF_8),
        "token",
        Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              int swept = atom.sweep(100);
              assertThat(swept).isEqualTo(1);
            });

    assertThat(atom.read(atom.atomKey("live"))).isPresent();
  }

  @Test
  void concurrentSweepDeletesAllExpiredAtoms() {
    int totalAtoms = 5000;
    for (int i = 0; i < totalAtoms; i++) {
      atom.create(
          atom.atomKey("concurrent-sweep-" + i),
          "data".getBytes(StandardCharsets.UTF_8),
          "token",
          Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> atom.read(atom.atomKey("concurrent-sweep-0")).isEmpty());

    int threadCount = 4;
    AtomicInteger totalDeleted = new AtomicInteger();

    IntStream.range(0, threadCount)
        .mapToObj(
            i ->
                Thread.ofVirtual()
                    .start(
                        () -> {
                          int swept;
                          do {
                            swept = atom.sweep(1000);
                            totalDeleted.addAndGet(swept);
                          } while (swept > 0);
                        }))
        .toList()
        .forEach(
            t -> {
              try {
                t.join(Duration.ofSeconds(10));
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            });

    assertThat(totalDeleted.get()).isEqualTo(totalAtoms);

    Integer remaining =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM substrate_atom", Integer.class);
    assertThat(remaining).isZero();
  }

  private DataSource createDataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(PostgresTestContainer.INSTANCE.getJdbcUrl());
    ds.setUsername(PostgresTestContainer.INSTANCE.getUsername());
    ds.setPassword(PostgresTestContainer.INSTANCE.getPassword());
    return ds;
  }
}
