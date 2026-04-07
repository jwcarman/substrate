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
package org.jwcarman.substrate.spi;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RawJournalEntryTest {

  private static final Instant NOW = Instant.now();

  @Test
  void equalsSameInstance() {
    RawJournalEntry entry = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    assertThat(entry).isEqualTo(entry);
  }

  @Test
  void equalsSameData() {
    RawJournalEntry entry1 = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    RawJournalEntry entry2 = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    assertThat(entry1).isEqualTo(entry2);
  }

  @Test
  void notEqualsDifferentId() {
    RawJournalEntry entry1 = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    RawJournalEntry entry2 = new RawJournalEntry("id2", "key1", "data".getBytes(UTF_8), NOW);
    assertThat(entry1).isNotEqualTo(entry2);
  }

  @Test
  void notEqualsDifferentKey() {
    RawJournalEntry entry1 = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    RawJournalEntry entry2 = new RawJournalEntry("id1", "key2", "data".getBytes(UTF_8), NOW);
    assertThat(entry1).isNotEqualTo(entry2);
  }

  @Test
  void notEqualsDifferentData() {
    RawJournalEntry entry1 = new RawJournalEntry("id1", "key1", "data1".getBytes(UTF_8), NOW);
    RawJournalEntry entry2 = new RawJournalEntry("id1", "key1", "data2".getBytes(UTF_8), NOW);
    assertThat(entry1).isNotEqualTo(entry2);
  }

  @Test
  void notEqualsDifferentTimestamp() {
    RawJournalEntry entry1 = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    RawJournalEntry entry2 =
        new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW.plusSeconds(1));
    assertThat(entry1).isNotEqualTo(entry2);
  }

  @Test
  void notEqualsNull() {
    RawJournalEntry entry = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    assertThat(entry).isNotEqualTo(null);
  }

  @Test
  void notEqualsDifferentType() {
    RawJournalEntry entry = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    assertThat(entry).isNotEqualTo("not a RawJournalEntry");
  }

  @Test
  void hashCodeSameForEqualEntries() {
    RawJournalEntry entry1 = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    RawJournalEntry entry2 = new RawJournalEntry("id1", "key1", "data".getBytes(UTF_8), NOW);
    assertThat(entry1).hasSameHashCodeAs(entry2);
  }

  @Test
  void hashCodeDiffersForDifferentData() {
    RawJournalEntry entry1 = new RawJournalEntry("id1", "key1", "data1".getBytes(UTF_8), NOW);
    RawJournalEntry entry2 = new RawJournalEntry("id1", "key1", "data2".getBytes(UTF_8), NOW);
    assertThat(entry1.hashCode()).isNotEqualTo(entry2.hashCode());
  }

  @Test
  void toStringContainsAllFields() {
    byte[] data = "hello".getBytes(UTF_8);
    RawJournalEntry entry = new RawJournalEntry("id1", "key1", data, NOW);
    String str = entry.toString();
    assertThat(str).startsWith("RawJournalEntry[").contains("id1", "key1", NOW.toString());
  }

  @Test
  void accessorsReturnConstructorValues() {
    byte[] data = "payload".getBytes(UTF_8);
    RawJournalEntry entry = new RawJournalEntry("myId", "myKey", data, NOW);
    assertThat(entry.id()).isEqualTo("myId");
    assertThat(entry.key()).isEqualTo("myKey");
    assertThat(entry.data()).isEqualTo(data);
    assertThat(entry.timestamp()).isEqualTo(NOW);
  }
}
