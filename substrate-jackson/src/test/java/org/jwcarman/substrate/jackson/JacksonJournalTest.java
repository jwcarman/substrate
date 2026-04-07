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
package org.jwcarman.substrate.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;
import tools.jackson.databind.json.JsonMapper;

class JacksonJournalTest {

  private InMemoryJournalSpi journal;
  private JsonMapper objectMapper;

  @BeforeEach
  void setUp() {
    journal = new InMemoryJournalSpi();
    objectMapper = JsonMapper.builder().build();
  }

  @Test
  void roundTripsSimpleRecord() {
    JacksonJournal<SimpleRecord> typed =
        new JacksonJournal<>(journal, objectMapper, SimpleRecord.class);
    String key = typed.journalKey("test");
    SimpleRecord original = new SimpleRecord("hello", 42, true);

    typed.append(key, original);

    try (Stream<JacksonJournalEntry<SimpleRecord>> stream = typed.readLast(key, 1)) {
      List<JacksonJournalEntry<SimpleRecord>> entries = stream.toList();
      assertThat(entries).hasSize(1);
      assertThat(entries.getFirst().data()).isEqualTo(original);
      assertThat(entries.getFirst().key()).isEqualTo(key);
      assertThat(entries.getFirst().id()).isNotNull();
      assertThat(entries.getFirst().timestamp()).isNotNull();
    }
  }

  @Test
  void roundTripsNestedRecord() {
    JacksonJournal<NestedRecord> typed =
        new JacksonJournal<>(journal, objectMapper, NestedRecord.class);
    String key = typed.journalKey("nested");
    NestedRecord original = new NestedRecord("outer", new SimpleRecord("inner", 7, false));

    typed.append(key, original);

    try (Stream<JacksonJournalEntry<NestedRecord>> stream = typed.readLast(key, 1)) {
      assertThat(stream.toList().getFirst().data()).isEqualTo(original);
    }
  }

  @Test
  void roundTripsRecordWithCollections() {
    JacksonJournal<CollectionRecord> typed =
        new JacksonJournal<>(journal, objectMapper, CollectionRecord.class);
    String key = typed.journalKey("collections");
    CollectionRecord original =
        new CollectionRecord(List.of("a", "b", "c"), Map.of("x", 1, "y", 2));

    typed.append(key, original);

    try (Stream<JacksonJournalEntry<CollectionRecord>> stream = typed.readLast(key, 1)) {
      assertThat(stream.toList().getFirst().data()).isEqualTo(original);
    }
  }

  @Test
  void readAfterReturnEntriesAfterGivenId() {
    JacksonJournal<SimpleRecord> typed =
        new JacksonJournal<>(journal, objectMapper, SimpleRecord.class);
    String key = typed.journalKey("cursor");

    String id1 = typed.append(key, new SimpleRecord("first", 1, true));
    typed.append(key, new SimpleRecord("second", 2, false));
    typed.append(key, new SimpleRecord("third", 3, true));

    try (Stream<JacksonJournalEntry<SimpleRecord>> stream = typed.readAfter(key, id1)) {
      List<JacksonJournalEntry<SimpleRecord>> entries = stream.toList();
      assertThat(entries).hasSize(2);
      assertThat(entries.get(0).data().name()).isEqualTo("second");
      assertThat(entries.get(1).data().name()).isEqualTo("third");
    }
  }

  @Test
  void appendWithTtlDelegates() {
    JacksonJournal<SimpleRecord> typed =
        new JacksonJournal<>(journal, objectMapper, SimpleRecord.class);
    String key = typed.journalKey("ttl");

    String id = typed.append(key, new SimpleRecord("ttl-test", 1, true), Duration.ofMinutes(5));
    assertThat(id).isNotNull();

    try (Stream<JacksonJournalEntry<SimpleRecord>> stream = typed.readLast(key, 1)) {
      assertThat(stream.toList().getFirst().data().name()).isEqualTo("ttl-test");
    }
  }

  @Test
  void completeDelegates() {
    JacksonJournal<SimpleRecord> typed =
        new JacksonJournal<>(journal, objectMapper, SimpleRecord.class);
    String key = typed.journalKey("complete");
    typed.append(key, new SimpleRecord("data", 1, true));

    typed.complete(key);
    // No exception means delegation succeeded
  }

  @Test
  void deleteDelegates() {
    JacksonJournal<SimpleRecord> typed =
        new JacksonJournal<>(journal, objectMapper, SimpleRecord.class);
    String key = typed.journalKey("delete");
    typed.append(key, new SimpleRecord("data", 1, true));

    typed.delete(key);

    try (Stream<JacksonJournalEntry<SimpleRecord>> stream = typed.readLast(key, 10)) {
      assertThat(stream.toList()).isEmpty();
    }
  }

  @Test
  void journalKeyDelegates() {
    JacksonJournal<SimpleRecord> typed =
        new JacksonJournal<>(journal, objectMapper, SimpleRecord.class);
    String key = typed.journalKey("test-name");
    assertThat(key).isNotNull();
    assertThat(key).isEqualTo(journal.journalKey("test-name"));
  }

  record SimpleRecord(String name, int value, boolean active) {}

  record NestedRecord(String label, SimpleRecord nested) {}

  record CollectionRecord(List<String> items, Map<String, Integer> scores) {}
}
