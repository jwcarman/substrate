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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class MongoDbJournalSpiTest {

  @Mock private MongoTemplate mongoTemplate;

  private MongoDbJournalSpi journal;

  @BeforeEach
  void setUp() {
    journal =
        new MongoDbJournalSpi(
            mongoTemplate, "substrate:journal:", "substrate_journal", Duration.ofHours(24));
  }

  @Test
  void appendInsertsDocumentWithCorrectFields() {
    journal.append(
        "substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
    verify(mongoTemplate).insert(captor.capture(), eq("substrate_journal"));

    Document doc = captor.getValue();
    assertThat(doc.getString("key")).isEqualTo("substrate:journal:test");
    assertThat(doc.getString("entryId"))
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    assertThat(doc.get("data", Binary.class).getData())
        .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    assertThat(doc.get("timestamp")).isNotNull();
    assertThat(doc.get("expireAt")).isNotNull();
  }

  @Test
  void appendReturnsUuidV7Id() {
    String id =
        journal.append(
            "substrate:journal:test",
            "hello".getBytes(StandardCharsets.UTF_8),
            Duration.ofHours(1));
    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }

  @Test
  void appendWithZeroTtlOmitsExpireAtField() {
    MongoDbJournalSpi noTtlJournal =
        new MongoDbJournalSpi(
            mongoTemplate, "substrate:journal:", "substrate_journal", Duration.ZERO);
    noTtlJournal.append(
        "substrate:journal:test", "data".getBytes(StandardCharsets.UTF_8), Duration.ZERO);

    ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
    verify(mongoTemplate).insert(captor.capture(), eq("substrate_journal"));

    assertThat(captor.getValue().containsKey("expireAt")).isFalse();
  }

  @Test
  void completeInsertsCompletionMarker() {
    journal.complete("substrate:journal:test");

    ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
    verify(mongoTemplate).insert(captor.capture(), eq("substrate_journal"));

    Document doc = captor.getValue();
    assertThat(doc.getString("key")).isEqualTo("substrate:journal:test");
    assertThat(doc.getString("entryId")).isEqualTo("COMPLETED");
  }

  @Test
  void readAfterQueriesWithCorrectCriteria() {
    when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("substrate_journal")))
        .thenReturn(List.of());

    journal.readAfter("substrate:journal:test", "00000000-0000-0000-0000-000000000000");

    verify(mongoTemplate).find(any(Query.class), eq(Document.class), eq("substrate_journal"));
  }

  @Test
  void readLastQueriesCollection() {
    when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("substrate_journal")))
        .thenReturn(List.of());

    journal.readLast("substrate:journal:test", 5);

    verify(mongoTemplate).find(any(Query.class), eq(Document.class), eq("substrate_journal"));
  }

  @Test
  void deleteRemovesMatchingDocuments() {
    journal.delete("substrate:journal:test");

    verify(mongoTemplate).remove(any(Query.class), eq("substrate_journal"));
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void mapDocumentWithNullTimestampFieldReturnsNullTimestamp() {
    Document doc = new Document();
    doc.put("key", "substrate:journal:test");
    doc.put("entryId", "entry-1");
    doc.put("data", new Binary("hello".getBytes(StandardCharsets.UTF_8)));
    // No timestamp field

    when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("substrate_journal")))
        .thenReturn(List.of(doc));

    List<RawJournalEntry> entries =
        journal.readAfter("substrate:journal:test", "00000000-0000-0000-0000-000000000000");

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp()).isNull();
  }

  @Test
  void mapDocumentWithNullDataFieldReturnsEmptyByteArray() {
    Document doc = new Document();
    doc.put("key", "substrate:journal:test");
    doc.put("entryId", "entry-1");
    doc.put("timestamp", new Date());
    // No data field

    when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("substrate_journal")))
        .thenReturn(List.of(doc));

    List<RawJournalEntry> entries =
        journal.readAfter("substrate:journal:test", "00000000-0000-0000-0000-000000000000");

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().data()).isEmpty();
  }
}
