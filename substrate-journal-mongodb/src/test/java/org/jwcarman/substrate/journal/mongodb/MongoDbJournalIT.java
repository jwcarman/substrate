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
package org.jwcarman.substrate.journal.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.RawJournalEntry;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MongoDbJournalSpiIT {

  @Container static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

  private MongoDbJournalSpi journal;

  @BeforeEach
  void setUp() {
    MongoClient client = MongoClients.create(mongo.getConnectionString());
    MongoTemplate mongoTemplate = new MongoTemplate(client, "substrate_test");

    mongoTemplate.dropCollection("substrate_journal");

    journal =
        new MongoDbJournalSpi(
            mongoTemplate, "substrate:journal:", "substrate_journal", Duration.ofHours(24));
    journal.ensureIndexes();
  }

  @Test
  void appendReturnsUuidV7Id() {
    String key = journal.journalKey("append-test");
    String id = journal.append(key, "hello".getBytes(StandardCharsets.UTF_8));

    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }

  @Test
  void appendReturnsMonotonicallyIncreasingIds() {
    String key = journal.journalKey("mono");
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    String id2 = journal.append(key, "second".getBytes(StandardCharsets.UTF_8));

    assertThat(id2).isGreaterThan(id1);
  }

  @Test
  void readAfterReturnsEntriesInOrder() {
    String key = journal.journalKey("read-after");
    String id1 = journal.append(key, "payload1".getBytes(StandardCharsets.UTF_8));
    String id2 = journal.append(key, "payload2".getBytes(StandardCharsets.UTF_8));
    String id3 = journal.append(key, "payload3".getBytes(StandardCharsets.UTF_8));

    List<RawJournalEntry> entries = journal.readAfter(key, id1);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo(id2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("payload2");
    assertThat(entries.get(1).id()).isEqualTo(id3);
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("payload3");
  }

  @Test
  void readAfterReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent");
    List<RawJournalEntry> entries = journal.readAfter(key, "00000000-0000-0000-0000-000000000000");
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsLastNInChronologicalOrder() {
    String key = journal.journalKey("read-last");
    journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "second".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "third".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "fourth".getBytes(StandardCharsets.UTF_8));

    List<RawJournalEntry> entries = journal.readLast(key, 2);

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("third");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("fourth");
  }

  @Test
  void readLastReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent");
    List<RawJournalEntry> entries = journal.readLast(key, 5);
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsAllWhenCountExceedsSize() {
    String key = journal.journalKey("small");
    journal.append(key, "one".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "two".getBytes(StandardCharsets.UTF_8));

    List<RawJournalEntry> entries = journal.readLast(key, 100);

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("one");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("two");
  }

  @Test
  void completeMarksJournalAsDone() {
    String key = journal.journalKey("complete-test");
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8));
    journal.complete(key);

    List<RawJournalEntry> entries = journal.readLast(key, 100);
    assertThat(entries).hasSize(1);
    assertThat(new String(entries.getFirst().data(), StandardCharsets.UTF_8)).isEqualTo("data");
  }

  @Test
  void deleteRemovesAllEntries() {
    String key = journal.journalKey("delete-test");
    journal.append(key, "hello".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "world".getBytes(StandardCharsets.UTF_8));

    journal.delete(key);

    List<RawJournalEntry> entries = journal.readLast(key, 100);
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteDoesNotAffectOtherJournals() {
    String key1 = journal.journalKey("a");
    String key2 = journal.journalKey("b");
    journal.append(key1, "a-event".getBytes(StandardCharsets.UTF_8));
    journal.append(key2, "b-event".getBytes(StandardCharsets.UTF_8));

    journal.delete(key1);

    assertThat(journal.readLast(key1, 100)).isEmpty();
    assertThat(journal.readLast(key2, 100)).hasSize(1);
  }

  @Test
  void timestampIsPreserved() {
    String key = journal.journalKey("time");
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8));

    List<RawJournalEntry> entries = journal.readLast(key, 1);
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp()).isNotNull();
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void indexesAreCreated() {
    // ensureIndexes was called in setUp; calling again should not throw
    assertThatNoException().isThrownBy(() -> journal.ensureIndexes());
  }

  @Test
  void appendWithCustomTtl() {
    String key = journal.journalKey("custom-ttl");
    String id =
        journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(10));

    assertThat(id).isNotEmpty();
    List<RawJournalEntry> entries = journal.readLast(key, 1);
    assertThat(entries).hasSize(1);
  }

  @Test
  void readAfterExcludesCompletionMarker() {
    String key = journal.journalKey("completed-read-after");
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    journal.complete(key);

    List<RawJournalEntry> entries = journal.readAfter(key, id1);
    assertThat(entries).isEmpty();
  }
}
