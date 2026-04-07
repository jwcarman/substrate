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
package org.jwcarman.substrate.journal.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.JournalEntry;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CassandraJournalSpiIT {

  @Container static CassandraContainer cassandra = new CassandraContainer("cassandra:4.1");

  private CassandraJournalSpi journal;
  private CqlSession session;

  @BeforeEach
  void setUp() {
    session =
        CqlSession.builder()
            .addContactPoint(
                new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042)))
            .withLocalDatacenter("datacenter1")
            .build();

    session.execute(
        "CREATE KEYSPACE IF NOT EXISTS substrate_test"
            + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
    session.execute("USE substrate_test");
    session.execute(
        "CREATE TABLE IF NOT EXISTS substrate_journal ("
            + "key TEXT, "
            + "entry_id TIMEUUID, "
            + "data BLOB, "
            + "timestamp TIMESTAMP, "
            + "PRIMARY KEY (key, entry_id)"
            + ") WITH CLUSTERING ORDER BY (entry_id ASC)");
    session.execute("TRUNCATE substrate_journal");

    journal =
        new CassandraJournalSpi(session, "substrate:journal:", "substrate_journal", Duration.ZERO);
  }

  @Test
  void appendReturnsTimeuuidId() {
    String key = journal.journalKey("append-test");
    String id = journal.append(key, "hello".getBytes(StandardCharsets.UTF_8));

    UUID uuid = UUID.fromString(id);
    assertThat(uuid.version()).isEqualTo(1);
  }

  @Test
  void appendReturnsMonotonicallyIncreasingIds() {
    String key = journal.journalKey("mono");
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    String id2 = journal.append(key, "second".getBytes(StandardCharsets.UTF_8));

    UUID uuid1 = UUID.fromString(id1);
    UUID uuid2 = UUID.fromString(id2);
    assertThat(uuid2.timestamp()).isGreaterThanOrEqualTo(uuid1.timestamp());
  }

  @Test
  void readAfterReturnsEntriesInOrder() {
    String key = journal.journalKey("read-after");
    String id1 = journal.append(key, "payload1".getBytes(StandardCharsets.UTF_8));
    String id2 = journal.append(key, "payload2".getBytes(StandardCharsets.UTF_8));
    String id3 = journal.append(key, "payload3".getBytes(StandardCharsets.UTF_8));

    List<JournalEntry> entries = journal.readAfter(key, id1).toList();

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo(id2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("payload2");
    assertThat(entries.get(1).id()).isEqualTo(id3);
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("payload3");
  }

  @Test
  void readAfterReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent");
    UUID dummyUuid = com.datastax.oss.driver.api.core.uuid.Uuids.timeBased();
    List<JournalEntry> entries = journal.readAfter(key, dummyUuid.toString()).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsLastNInChronologicalOrder() {
    String key = journal.journalKey("read-last");
    journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "second".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "third".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "fourth".getBytes(StandardCharsets.UTF_8));

    List<JournalEntry> entries = journal.readLast(key, 2).toList();

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("third");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("fourth");
  }

  @Test
  void readLastReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent");
    List<JournalEntry> entries = journal.readLast(key, 5).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsAllWhenCountExceedsSize() {
    String key = journal.journalKey("small");
    journal.append(key, "one".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "two".getBytes(StandardCharsets.UTF_8));

    List<JournalEntry> entries = journal.readLast(key, 100).toList();

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("one");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("two");
  }

  @Test
  void completeMarksJournalAsDone() {
    String key = journal.journalKey("complete-test");
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8));
    journal.complete(key);

    // Completion marker should not appear in readLast
    List<JournalEntry> entries = journal.readLast(key, 100).toList();
    assertThat(entries).hasSize(1);
    assertThat(new String(entries.getFirst().data(), StandardCharsets.UTF_8)).isEqualTo("data");
  }

  @Test
  void readAfterExcludesCompletionMarker() {
    String key = journal.journalKey("completed-read-after");
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    journal.complete(key);

    List<JournalEntry> entries = journal.readAfter(key, id1).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteRemovesAllEntries() {
    String key = journal.journalKey("delete-test");
    journal.append(key, "hello".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "world".getBytes(StandardCharsets.UTF_8));

    journal.delete(key);

    List<JournalEntry> entries = journal.readLast(key, 100).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteDoesNotAffectOtherJournals() {
    String key1 = journal.journalKey("a");
    String key2 = journal.journalKey("b");
    journal.append(key1, "a-event".getBytes(StandardCharsets.UTF_8));
    journal.append(key2, "b-event".getBytes(StandardCharsets.UTF_8));

    journal.delete(key1);

    assertThat(journal.readLast(key1, 100).toList()).isEmpty();
    assertThat(journal.readLast(key2, 100).toList()).hasSize(1);
  }

  @Test
  void timestampIsPreserved() {
    String key = journal.journalKey("time");
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8));

    List<JournalEntry> entries = journal.readLast(key, 1).toList();
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp()).isNotNull();
  }

  @Test
  void appendWithTtlDoesNotError() {
    CassandraJournalSpi ttlJournal =
        new CassandraJournalSpi(
            session, "substrate:journal:", "substrate_journal", Duration.ofHours(1));

    String key = ttlJournal.journalKey("ttl-test");
    String id = ttlJournal.append(key, "ttl-event".getBytes(StandardCharsets.UTF_8));
    assertThat(id).isNotEmpty();

    List<JournalEntry> entries = ttlJournal.readLast(key, 1).toList();
    assertThat(entries).hasSize(1);
    assertThat(new String(entries.getFirst().data(), StandardCharsets.UTF_8))
        .isEqualTo("ttl-event");
  }

  @Test
  void appendWithCustomTtl() {
    String key = journal.journalKey("custom-ttl");
    String id =
        journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(10));

    assertThat(id).isNotEmpty();
    List<JournalEntry> entries = journal.readLast(key, 1).toList();
    assertThat(entries).hasSize(1);
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void schemaAutoCreationHandlesExistingTable() {
    // createSchema was already called in setUp; calling again should not throw
    assertThatNoException().isThrownBy(() -> journal.createSchema());
  }
}
