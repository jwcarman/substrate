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
package org.jwcarman.substrate.core.memory.journal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.journal.RawJournalEntry;

class InMemoryJournalSpiTest {

  private static final String KEY = "substrate:journal:test";

  private InMemoryJournalSpi journal;

  @BeforeEach
  void setUp() {
    journal = new InMemoryJournalSpi(100);
  }

  @Test
  void appendReturnsMonotonicallyIncreasingIds() {
    String id1 = journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    String id2 = journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));
    String id3 = journal.append(KEY, "data3".getBytes(UTF_8), Duration.ofHours(1));

    assertTrue(parseId(id1) < parseId(id2));
    assertTrue(parseId(id2) < parseId(id3));
  }

  @Test
  void appendAndReadAfterReturnsEntriesAfterCursor() {
    String id1 = journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    String id2 = journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));
    String id3 = journal.append(KEY, "data3".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(KEY, id1);

    assertEquals(2, entries.size());
    assertEquals(id2, entries.get(0).id());
    assertEquals(id3, entries.get(1).id());
  }

  @Test
  void readAfterWithZeroCursorReturnsAllEntries() {
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(KEY, "0-0");

    assertEquals(2, entries.size());
  }

  @Test
  void readAfterOnNonExistentKeyReturnsEmpty() {
    List<RawJournalEntry> entries = journal.readAfter("nonexistent", "0-0");

    assertTrue(entries.isEmpty());
  }

  @Test
  void readLastReturnsLastNEntriesInOrder() {
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(KEY, "data3".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(KEY, "data4".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readLast(KEY, 2);

    assertEquals(2, entries.size());
    assertArrayEquals("data3".getBytes(UTF_8), entries.get(0).data());
    assertArrayEquals("data4".getBytes(UTF_8), entries.get(1).data());
  }

  @Test
  void readLastWithCountExceedingSizeReturnsAll() {
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readLast(KEY, 10);

    assertEquals(2, entries.size());
  }

  @Test
  void readLastOnNonExistentKeyReturnsEmpty() {
    List<RawJournalEntry> entries = journal.readLast("nonexistent", 5);

    assertTrue(entries.isEmpty());
  }

  @Test
  void evictionRemovesOldestEntriesWhenFull() {
    InMemoryJournalSpi smallJournal = new InMemoryJournalSpi(3);

    smallJournal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    smallJournal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));
    smallJournal.append(KEY, "data3".getBytes(UTF_8), Duration.ofHours(1));
    smallJournal.append(KEY, "data4".getBytes(UTF_8), Duration.ofHours(1));
    smallJournal.append(KEY, "data5".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = smallJournal.readAfter(KEY, "0-0");

    assertEquals(3, entries.size());
    assertArrayEquals("data3".getBytes(UTF_8), entries.get(0).data());
    assertArrayEquals("data4".getBytes(UTF_8), entries.get(1).data());
    assertArrayEquals("data5".getBytes(UTF_8), entries.get(2).data());
  }

  @Test
  void deleteRemovesJournal() {
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    journal.delete(KEY);

    List<RawJournalEntry> entries = journal.readAfter(KEY, "0-0");
    assertTrue(entries.isEmpty());
  }

  @Test
  void appendPreservesEntryFields() {
    String id = journal.append(KEY, "myData".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(KEY, "0-0");

    assertEquals(1, entries.size());
    RawJournalEntry stored = entries.getFirst();
    assertEquals(id, stored.id());
    assertEquals(KEY, stored.key());
    assertArrayEquals("myData".getBytes(UTF_8), stored.data());
    assertNotNull(stored.timestamp());
  }

  @Test
  void readAfterHandlesIdWithoutDash() {
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(KEY, "0");

    assertEquals(1, entries.size());
  }

  @Test
  void separateKeysAreIndependent() {
    String key1 = "substrate:journal:one";
    String key2 = "substrate:journal:two";

    journal.append(key1, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(key2, "data2".getBytes(UTF_8), Duration.ofHours(1));

    assertEquals(1, journal.readAfter(key1, "0-0").size());
    assertEquals(1, journal.readAfter(key2, "0-0").size());

    journal.delete(key1);

    assertTrue(journal.readAfter(key1, "0-0").isEmpty());
    assertEquals(1, journal.readAfter(key2, "0-0").size());
  }

  @Test
  void completeBlocksFurtherAppends() {
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    journal.complete(KEY);

    byte[] data2 = "data2".getBytes(UTF_8);
    assertThrows(
        IllegalStateException.class, () -> journal.append(KEY, data2, Duration.ofHours(1)));
  }

  @Test
  void deleteRemovesCompletedStatus() {
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY);

    journal.delete(KEY);

    assertDoesNotThrow(() -> journal.append(KEY, "data3".getBytes(UTF_8), Duration.ofHours(1)));
  }

  @Test
  void expiredEntriesAreExcludedFromReads() {
    journal.append(KEY, "short-lived".getBytes(UTF_8), Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              List<RawJournalEntry> entries = journal.readAfter(KEY, "0-0");
              assertTrue(entries.isEmpty());
            });
  }

  private static long parseId(String id) {
    int dash = id.indexOf('-');
    return Long.parseLong(dash >= 0 ? id.substring(0, dash) : id);
  }
}
