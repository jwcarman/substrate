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
package org.jwcarman.substrate.postgresql.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.jwcarman.substrate.journal.JournalAlreadyExistsException;
import org.jwcarman.substrate.journal.JournalCompletedException;
import org.jwcarman.substrate.journal.JournalExpiredException;
import org.jwcarman.substrate.postgresql.PostgresTestContainer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class PostgresJournalSpiIT {

  private static final Duration ONE_HOUR = Duration.ofHours(1);

  private PostgresJournalSpi journal;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    DataSource dataSource = createDataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);

    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    populator.addScript(new ClassPathResource("db/substrate/postgresql/V1__create_journal.sql"));
    populator.execute(dataSource);

    jdbcTemplate.update("DELETE FROM substrate_journal_entries");
    jdbcTemplate.update("DELETE FROM substrate_journal");

    journal = new PostgresJournalSpi(jdbcTemplate, "substrate:journal:", 100_000);
  }

  @Test
  void existsReturnsFalseForNeverCreatedKey() {
    assertThat(journal.exists(journal.journalKey("never"))).isFalse();
  }

  @Test
  void existsReturnsTrueAfterAppend() {
    String key = journal.journalKey("exists-test");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    assertThat(journal.exists(key)).isTrue();
  }

  @Test
  void appendAndReadAfterFullLifecycle() {
    String key = journal.journalKey("test-stream");
    journal.create(key, Duration.ofHours(1));
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    String id2 =
        journal.append(key, "second".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    String id3 = journal.append(key, "third".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(key, id1);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo(id2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("second");
    assertThat(entries.get(0).key()).isEqualTo(key);
    assertThat(entries.get(0).timestamp()).isNotNull();
    assertThat(entries.get(1).id()).isEqualTo(id3);
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("third");
  }

  @Test
  void readLastReturnsEntriesInChronologicalOrder() {
    String key = journal.journalKey("last-test");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "a".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key, "b".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key, "c".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readLast(key, 2);

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("b");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("c");
  }

  @Test
  void readAfterReturnsEmptyForNonexistentStream() {
    List<RawJournalEntry> entries = journal.readAfter("nonexistent:key", "0");
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteRemovesAllEntries() {
    String key = journal.journalKey("delete-test");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.delete(key);

    List<RawJournalEntry> entries = journal.readLast(key, 100);
    assertThat(entries).isEmpty();
  }

  @Test
  void completeMarksTheJournalComplete() {
    String key = journal.journalKey("complete-test");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key, Duration.ofHours(1));

    assertThat(journal.isComplete(key)).isTrue();
  }

  @Test
  void deleteRemovesCompletionState() {
    String key = journal.journalKey("complete-delete-test");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key, Duration.ofHours(1));
    journal.delete(key);

    assertThat(journal.isComplete(key)).isFalse();
    assertThat(journal.exists(key)).isFalse();
  }

  @Test
  void deleteDoesNotAffectOtherStreams() {
    String stream1 = journal.journalKey("stream-a");
    String stream2 = journal.journalKey("stream-b");
    journal.create(stream1, Duration.ofHours(1));
    journal.create(stream2, Duration.ofHours(1));
    journal.append(stream1, "a-event".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(stream2, "b-event".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    journal.delete(stream1);

    assertThat(journal.readLast(stream1, 100)).isEmpty();
    assertThat(journal.readLast(stream2, 100)).hasSize(1);
  }

  @Test
  void appendReturnsMonotonicId() {
    String key = journal.journalKey("monotonic-test");
    journal.create(key, Duration.ofHours(1));
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    String id2 =
        journal.append(key, "second".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    assertThat(Long.parseLong(id1)).isPositive();
    assertThat(Long.parseLong(id2)).isGreaterThan(Long.parseLong(id1));
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void trimRemovesOldEntriesWhenExceedingMaxLen() {
    PostgresJournalSpi smallJournal = new PostgresJournalSpi(jdbcTemplate, "substrate:journal:", 5);

    String key = smallJournal.journalKey("trim-test");
    smallJournal.create(key, Duration.ofHours(1));
    for (int i = 0; i < 10; i++) {
      smallJournal.append(
          key, ("event-" + i).getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    }

    // All 10 events exist because trim only fires every 100 appends
    List<RawJournalEntry> allEntries = smallJournal.readLast(key, 100);
    assertThat(allEntries).hasSize(10);
  }

  @Test
  void completeIsIdempotentAndTheLatestRetentionTtlWins() {
    String key = journal.journalKey("idempotent-complete");
    journal.create(key, Duration.ofHours(1));
    journal.complete(key, Duration.ofMillis(50));
    journal.complete(key, Duration.ofHours(1));

    assertThat(journal.isComplete(key)).isTrue();
    assertThat(journal.exists(key)).isTrue();
  }

  @Test
  void isCompleteReturnsFalseForNonCompletedJournal() {
    String key = journal.journalKey("incomplete-test");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    assertThat(journal.isComplete(key)).isFalse();
  }

  @Test
  void isCompleteReturnsTrueAfterComplete() {
    String key = journal.journalKey("is-complete-test");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key, Duration.ofHours(1));

    assertThat(journal.isComplete(key)).isTrue();
  }

  @Test
  void trimRemovesOldEntriesAfter100Appends() {
    PostgresJournalSpi smallJournal =
        new PostgresJournalSpi(jdbcTemplate, "substrate:journal:", 10);

    String key = smallJournal.journalKey("trim-100-test");
    smallJournal.create(key, Duration.ofHours(1));
    for (int i = 0; i < 100; i++) {
      smallJournal.append(
          key, ("event-" + i).getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    }

    List<RawJournalEntry> entries = smallJournal.readLast(key, 100);
    assertThat(entries).hasSizeLessThanOrEqualTo(10);
    assertThat(new String(entries.getLast().data(), StandardCharsets.UTF_8)).isEqualTo("event-99");
  }

  @Test
  void readLastOmitsEntriesPastTheirEntryTtl() {
    String key = journal.journalKey("entry-ttl-read");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "ephemeral".getBytes(StandardCharsets.UTF_8), Duration.ofMillis(50));
    journal.append(key, "durable".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(journal.readLast(key, 100))
                    .extracting(e -> new String(e.data(), StandardCharsets.UTF_8))
                    .containsExactly("durable"));
  }

  @Test
  void sweepDeletesEntriesPastTheirEntryTtl() {
    String key = journal.journalKey("entry-ttl-sweep");
    journal.create(key, Duration.ofHours(1));
    for (int i = 0; i < 10; i++) {
      journal.append(key, ("event-" + i).getBytes(StandardCharsets.UTF_8), Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(journal.sweep(100)).isEqualTo(10));

    Integer remaining =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_journal_entries WHERE key = ?", Integer.class, key);
    assertThat(remaining).isZero();
  }

  @Test
  void sweepLeavesEntriesWithinTheirEntryTtl() {
    String key = journal.journalKey("entry-ttl-sweep-live");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "durable".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    assertThat(journal.sweep(100)).isZero();
    assertThat(journal.readLast(key, 100)).hasSize(1);
  }

  @Test
  void sweepStopsAtTheRequestedLimit() {
    String key = journal.journalKey("entry-ttl-sweep-limit");
    journal.create(key, Duration.ofHours(1));
    for (int i = 0; i < 10; i++) {
      journal.append(key, ("event-" + i).getBytes(StandardCharsets.UTF_8), Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(journal.sweep(4)).isEqualTo(4));
  }

  @Test
  void existsReturnsFalseOnceTheInactivityTtlElapses() {
    String key = journal.journalKey("inactivity-expiry");
    journal.create(key, Duration.ofMillis(50));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(journal.exists(key)).isFalse());
  }

  @Test
  void appendPushesOutTheInactivityDeadline() {
    String key = journal.journalKey("inactivity-reset");
    journal.create(key, Duration.ofMillis(500));
    AtomicInteger appended = new AtomicInteger();

    // Append every 200ms for longer than the 500ms inactivity TTL. Each append has to
    // push the deadline out; if one does not, the journal dies and append throws.
    await()
        .pollDelay(Duration.ofMillis(200))
        .pollInterval(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              journal.append(
                  key, ("event-" + appended.get()).getBytes(StandardCharsets.UTF_8), ONE_HOUR);
              return appended.incrementAndGet() == 4;
            });

    assertThat(journal.exists(key)).isTrue();
    assertThat(journal.readLast(key, 100)).hasSize(4);
  }

  @Test
  void appendThrowsOnceTheJournalIsDead() {
    String key = journal.journalKey("append-after-death");
    byte[] late = "late".getBytes(StandardCharsets.UTF_8);
    journal.create(key, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.append(key, late, ONE_HOUR))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void appendThrowsForAJournalThatWasNeverCreated() {
    String key = journal.journalKey("never-created");
    byte[] data = "data".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> journal.append(key, data, ONE_HOUR))
        .isInstanceOf(JournalExpiredException.class);
  }

  @Test
  void readLastThrowsOnceTheJournalIsDead() {
    String key = journal.journalKey("read-after-death");
    journal.create(key, Duration.ofMillis(50));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.readLast(key, 100))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void readAfterThrowsOnceTheJournalIsDead() {
    String key = journal.journalKey("read-after-cursor-death");
    journal.create(key, Duration.ofMillis(50));
    String id = journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.readAfter(key, id))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void createThrowsWhenALiveJournalAlreadyExists() {
    String key = journal.journalKey("duplicate-create");
    journal.create(key, Duration.ofHours(1));

    assertThatThrownBy(() -> journal.create(key, ONE_HOUR))
        .isInstanceOf(JournalAlreadyExistsException.class);
  }

  @Test
  void createReplacesADeadJournalAndDiscardsItsEntries() {
    String key = journal.journalKey("recreate-dead");
    journal.create(key, Duration.ofMillis(50));
    journal.append(key, "stale".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              journal.create(key, Duration.ofHours(1));
              assertThat(journal.readLast(key, 100)).isEmpty();
            });
  }

  @Test
  void appendThrowsOnceTheJournalIsCompleted() {
    String key = journal.journalKey("append-after-complete");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key, Duration.ofHours(1));

    byte[] late = "late".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> journal.append(key, late, ONE_HOUR))
        .isInstanceOf(JournalCompletedException.class);
  }

  @Test
  void completedJournalStaysReadableWithinItsRetentionTtl() {
    String key = journal.journalKey("retention-live");
    journal.create(key, Duration.ofMillis(50));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key, Duration.ofHours(1));

    assertThat(journal.exists(key)).isTrue();
    assertThat(journal.isComplete(key)).isTrue();
    assertThat(journal.readLast(key, 100)).hasSize(1);
  }

  @Test
  void completedJournalDiesOnceItsRetentionTtlElapses() {
    String key = journal.journalKey("retention-expiry");
    journal.create(key, Duration.ofHours(1));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              assertThat(journal.exists(key)).isFalse();
              assertThat(journal.isComplete(key)).isFalse();
            });
  }

  @Test
  void completeWithZeroRetentionRetainsTheJournalIndefinitely() {
    String key = journal.journalKey("retention-forever");
    journal.create(key, Duration.ofMillis(50));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key, Duration.ZERO);

    assertThat(journal.exists(key)).isTrue();
    assertThat(journal.isComplete(key)).isTrue();
    assertThat(journal.sweep(100)).isZero();
  }

  @Test
  void completeThrowsOnceTheJournalIsDead() {
    String key = journal.journalKey("complete-after-death");
    journal.create(key, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.complete(key, ONE_HOUR))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void sweepDeletesDeadJournalsAndTheirEntries() {
    String key = journal.journalKey("sweep-dead-journal");
    journal.create(key, Duration.ofMillis(50));
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(journal.sweep(100)).isPositive());

    Integer entries =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_journal_entries WHERE key = ?", Integer.class, key);
    assertThat(entries).isZero();
    assertThat(journal.exists(key)).isFalse();
  }

  private DataSource createDataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(PostgresTestContainer.INSTANCE.getJdbcUrl());
    ds.setUsername(PostgresTestContainer.INSTANCE.getUsername());
    ds.setPassword(PostgresTestContainer.INSTANCE.getPassword());
    return ds;
  }
}
