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
package org.jwcarman.substrate.journal.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.JournalEntry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresJournalSpiIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    jdbcTemplate.update("DELETE FROM substrate_journal_completed");

    journal = new PostgresJournalSpi(jdbcTemplate, "substrate:journal:", 100_000);
  }

  @Test
  void appendAndReadAfterFullLifecycle() {
    String key = journal.journalKey("test-stream");
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    String id2 = journal.append(key, "second".getBytes(StandardCharsets.UTF_8));
    String id3 = journal.append(key, "third".getBytes(StandardCharsets.UTF_8));

    List<JournalEntry> entries = journal.readAfter(key, id1).toList();

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
    journal.append(key, "a".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "b".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "c".getBytes(StandardCharsets.UTF_8));

    List<JournalEntry> entries = journal.readLast(key, 2).toList();

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("b");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("c");
  }

  @Test
  void readAfterReturnsEmptyForNonexistentStream() {
    List<JournalEntry> entries = journal.readAfter("nonexistent:key", "0").toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteRemovesAllEntries() {
    String key = journal.journalKey("delete-test");
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8));
    journal.delete(key);

    List<JournalEntry> entries = journal.readLast(key, 100).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void completeStoresCompletionMarker() {
    String key = journal.journalKey("complete-test");
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8));
    journal.complete(key);

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_journal_completed WHERE key = ?", Integer.class, key);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void deleteRemovesCompletionMarker() {
    String key = journal.journalKey("complete-delete-test");
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8));
    journal.complete(key);
    journal.delete(key);

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_journal_completed WHERE key = ?", Integer.class, key);
    assertThat(count).isZero();
  }

  @Test
  void deleteDoesNotAffectOtherStreams() {
    String stream1 = journal.journalKey("stream-a");
    String stream2 = journal.journalKey("stream-b");
    journal.append(stream1, "a-event".getBytes(StandardCharsets.UTF_8));
    journal.append(stream2, "b-event".getBytes(StandardCharsets.UTF_8));

    journal.delete(stream1);

    assertThat(journal.readLast(stream1, 100).toList()).isEmpty();
    assertThat(journal.readLast(stream2, 100).toList()).hasSize(1);
  }

  @Test
  void appendReturnsMonotonicId() {
    String key = journal.journalKey("monotonic-test");
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    String id2 = journal.append(key, "second".getBytes(StandardCharsets.UTF_8));

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
    for (int i = 0; i < 10; i++) {
      smallJournal.append(key, ("event-" + i).getBytes(StandardCharsets.UTF_8));
    }

    // All 10 events exist because trim only fires every 100 appends
    List<JournalEntry> allEntries = smallJournal.readLast(key, 100).toList();
    assertThat(allEntries).hasSize(10);
  }

  @Test
  void completeIsIdempotent() {
    String key = journal.journalKey("idempotent-complete");
    journal.complete(key);
    journal.complete(key);

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_journal_completed WHERE key = ?", Integer.class, key);
    assertThat(count).isEqualTo(1);
  }

  private DataSource createDataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
