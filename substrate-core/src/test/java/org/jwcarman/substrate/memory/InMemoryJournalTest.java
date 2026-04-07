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
package org.jwcarman.substrate.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.JournalEntry;

class InMemoryJournalTest {

  private static final String KEY = "substrate:journal:test";

  private InMemoryJournal journal;

  @BeforeEach
  void setUp() {
    journal = new InMemoryJournal(100);
  }

  @Test
  void appendReturnsMonotonicallyIncreasingIds() {
    String id1 = journal.append(KEY, "data1");
    String id2 = journal.append(KEY, "data2");
    String id3 = journal.append(KEY, "data3");

    assertTrue(parseId(id1) < parseId(id2));
    assertTrue(parseId(id2) < parseId(id3));
  }

  @Test
  void appendAndReadAfterReturnsEntriesAfterCursor() {
    String id1 = journal.append(KEY, "data1");
    String id2 = journal.append(KEY, "data2");
    String id3 = journal.append(KEY, "data3");

    List<JournalEntry> entries = journal.readAfter(KEY, id1).toList();

    assertEquals(2, entries.size());
    assertEquals(id2, entries.get(0).id());
    assertEquals(id3, entries.get(1).id());
  }

  @Test
  void readAfterWithZeroCursorReturnsAllEntries() {
    journal.append(KEY, "data1");
    journal.append(KEY, "data2");

    List<JournalEntry> entries = journal.readAfter(KEY, "0-0").toList();

    assertEquals(2, entries.size());
  }

  @Test
  void readAfterOnNonExistentKeyReturnsEmpty() {
    List<JournalEntry> entries = journal.readAfter("nonexistent", "0-0").toList();

    assertTrue(entries.isEmpty());
  }

  @Test
  void readLastReturnsLastNEntriesInOrder() {
    journal.append(KEY, "data1");
    journal.append(KEY, "data2");
    journal.append(KEY, "data3");
    journal.append(KEY, "data4");

    List<JournalEntry> entries = journal.readLast(KEY, 2).toList();

    assertEquals(2, entries.size());
    assertEquals("data3", entries.get(0).data());
    assertEquals("data4", entries.get(1).data());
  }

  @Test
  void readLastWithCountExceedingSizeReturnsAll() {
    journal.append(KEY, "data1");
    journal.append(KEY, "data2");

    List<JournalEntry> entries = journal.readLast(KEY, 10).toList();

    assertEquals(2, entries.size());
  }

  @Test
  void readLastOnNonExistentKeyReturnsEmpty() {
    List<JournalEntry> entries = journal.readLast("nonexistent", 5).toList();

    assertTrue(entries.isEmpty());
  }

  @Test
  void evictionRemovesOldestEntriesWhenFull() {
    InMemoryJournal smallJournal = new InMemoryJournal(3);

    smallJournal.append(KEY, "data1");
    smallJournal.append(KEY, "data2");
    smallJournal.append(KEY, "data3");
    smallJournal.append(KEY, "data4");
    smallJournal.append(KEY, "data5");

    List<JournalEntry> entries = smallJournal.readAfter(KEY, "0-0").toList();

    assertEquals(3, entries.size());
    assertEquals("data3", entries.get(0).data());
    assertEquals("data4", entries.get(1).data());
    assertEquals("data5", entries.get(2).data());
  }

  @Test
  void deleteRemovesJournal() {
    journal.append(KEY, "data1");

    journal.delete(KEY);

    List<JournalEntry> entries = journal.readAfter(KEY, "0-0").toList();
    assertTrue(entries.isEmpty());
  }

  @Test
  void appendPreservesEntryFields() {
    String id = journal.append(KEY, "myData");

    List<JournalEntry> entries = journal.readAfter(KEY, "0-0").toList();

    assertEquals(1, entries.size());
    JournalEntry stored = entries.getFirst();
    assertEquals(id, stored.id());
    assertEquals(KEY, stored.key());
    assertEquals("myData", stored.data());
    assertNotNull(stored.timestamp());
  }

  @Test
  void readAfterHandlesIdWithoutDash() {
    journal.append(KEY, "data1");

    List<JournalEntry> entries = journal.readAfter(KEY, "0").toList();

    assertEquals(1, entries.size());
  }

  @Test
  void separateKeysAreIndependent() {
    String key1 = "substrate:journal:one";
    String key2 = "substrate:journal:two";

    journal.append(key1, "data1");
    journal.append(key2, "data2");

    assertEquals(1, journal.readAfter(key1, "0-0").toList().size());
    assertEquals(1, journal.readAfter(key2, "0-0").toList().size());

    journal.delete(key1);

    assertTrue(journal.readAfter(key1, "0-0").toList().isEmpty());
    assertEquals(1, journal.readAfter(key2, "0-0").toList().size());
  }

  @Test
  void completeBlocksFurtherAppends() {
    journal.append(KEY, "data1");

    journal.complete(KEY);

    assertThrows(IllegalStateException.class, () -> journal.append(KEY, "data2"));
  }

  @Test
  void deleteRemovesCompletedStatus() {
    journal.append(KEY, "data1");
    journal.complete(KEY);

    journal.delete(KEY);

    assertDoesNotThrow(() -> journal.append(KEY, "data3"));
  }

  private static long parseId(String id) {
    int dash = id.indexOf('-');
    return Long.parseLong(dash >= 0 ? id.substring(0, dash) : id);
  }
}
