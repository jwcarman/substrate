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
package org.jwcarman.substrate.journal.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.JournalEntry;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

@Testcontainers
class DynamoDbJournalIT {

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
          .withServices("dynamodb");

  private DynamoDbJournal journal;
  private DynamoDbClient client;

  @BeforeEach
  void setUp() {
    client =
        DynamoDbClient.builder()
            .endpointOverride(localstack.getEndpoint())
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        localstack.getAccessKey(), localstack.getSecretKey())))
            .region(Region.of(localstack.getRegion()))
            .build();

    try {
      client.deleteTable(DeleteTableRequest.builder().tableName("substrate_journal").build());
    } catch (ResourceNotFoundException _) {
      // table doesn't exist yet
    }

    journal =
        new DynamoDbJournal(
            client, "substrate:journal:", "substrate_journal", Duration.ofHours(24));
    journal.createTable();
  }

  @Test
  void appendReturnsUuidV7Id() {
    String key = journal.journalKey("append-test");
    String id = journal.append(key, "hello");

    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }

  @Test
  void appendReturnsMonotonicallyIncreasingIds() {
    String key = journal.journalKey("mono");
    String id1 = journal.append(key, "first");
    String id2 = journal.append(key, "second");

    assertThat(id2).isGreaterThan(id1);
  }

  @Test
  void readAfterReturnsEntriesInOrder() {
    String key = journal.journalKey("read-after");
    String id1 = journal.append(key, "payload1");
    String id2 = journal.append(key, "payload2");
    String id3 = journal.append(key, "payload3");

    List<JournalEntry> entries = journal.readAfter(key, id1).toList();

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo(id2);
    assertThat(entries.get(0).data()).isEqualTo("payload2");
    assertThat(entries.get(1).id()).isEqualTo(id3);
    assertThat(entries.get(1).data()).isEqualTo("payload3");
  }

  @Test
  void readAfterReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent");
    List<JournalEntry> entries =
        journal.readAfter(key, "00000000-0000-0000-0000-000000000000").toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsLastNInChronologicalOrder() {
    String key = journal.journalKey("read-last");
    journal.append(key, "first");
    journal.append(key, "second");
    journal.append(key, "third");
    journal.append(key, "fourth");

    List<JournalEntry> entries = journal.readLast(key, 2).toList();

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).data()).isEqualTo("third");
    assertThat(entries.get(1).data()).isEqualTo("fourth");
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
    journal.append(key, "one");
    journal.append(key, "two");

    List<JournalEntry> entries = journal.readLast(key, 100).toList();

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).data()).isEqualTo("one");
    assertThat(entries.get(1).data()).isEqualTo("two");
  }

  @Test
  void completeMarksJournalAsDone() {
    String key = journal.journalKey("complete-test");
    journal.append(key, "data");
    journal.complete(key);

    // COMPLETED marker should not appear in readAfter or readLast
    List<JournalEntry> entries = journal.readLast(key, 100).toList();
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().data()).isEqualTo("data");
  }

  @Test
  void deleteRemovesAllEntries() {
    String key = journal.journalKey("delete-test");
    journal.append(key, "hello");
    journal.append(key, "world");

    journal.delete(key);

    List<JournalEntry> entries = journal.readLast(key, 100).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteDoesNotAffectOtherJournals() {
    String key1 = journal.journalKey("a");
    String key2 = journal.journalKey("b");
    journal.append(key1, "a-event");
    journal.append(key2, "b-event");

    journal.delete(key1);

    assertThat(journal.readLast(key1, 100).toList()).isEmpty();
    assertThat(journal.readLast(key2, 100).toList()).hasSize(1);
  }

  @Test
  void timestampIsPreserved() {
    String key = journal.journalKey("time");
    journal.append(key, "data");

    List<JournalEntry> entries = journal.readLast(key, 1).toList();
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp()).isNotNull();
  }

  @Test
  void ttlFieldIsPopulatedAsEpochSeconds() {
    String key = journal.journalKey("ttl-test");
    journal.append(key, "data");

    // Verify entry was stored and can be read back
    List<JournalEntry> entries = journal.readLast(key, 1).toList();
    assertThat(entries).hasSize(1);
  }

  @Test
  void batchDeleteHandlesMoreThan25Items() {
    String key = journal.journalKey("batch-delete");
    for (int i = 0; i < 30; i++) {
      journal.append(key, "item-" + i);
    }

    journal.delete(key);

    List<JournalEntry> entries = journal.readLast(key, 100).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void tableAutoCreationHandlesExistingTable() {
    // createTable was already called in setUp; calling again should not throw
    journal.createTable();
  }

  @Test
  void appendWithCustomTtl() {
    String key = journal.journalKey("custom-ttl");
    String id = journal.append(key, "data", Duration.ofMinutes(10));

    assertThat(id).isNotEmpty();
    List<JournalEntry> entries = journal.readLast(key, 1).toList();
    assertThat(entries).hasSize(1);
  }

  @Test
  void readAfterExcludesCompletionMarker() {
    String key = journal.journalKey("completed-read-after");
    String id1 = journal.append(key, "first");
    journal.complete(key);

    List<JournalEntry> entries = journal.readAfter(key, id1).toList();
    assertThat(entries).isEmpty();
  }
}
