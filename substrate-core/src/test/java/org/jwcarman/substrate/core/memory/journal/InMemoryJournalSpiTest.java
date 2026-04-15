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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.jwcarman.substrate.journal.JournalAlreadyExistsException;
import org.jwcarman.substrate.journal.JournalCompletedException;
import org.jwcarman.substrate.journal.JournalExpiredException;

class InMemoryJournalSpiTest {

  private static final String KEY = "substrate:journal:test";

  private InMemoryJournalSpi journal;

  @BeforeEach
  void setUp() {
    journal = new InMemoryJournalSpi(100);
  }

  @Test
  void existsReturnsFalseForNeverCreatedKey() {
    assertThat(journal.exists(KEY)).isFalse();
  }

  @Test
  void existsReturnsTrueForCreatedKey() {
    journal.create(KEY, Duration.ofHours(1));
    assertThat(journal.exists(KEY)).isTrue();
  }

  @Test
  void existsReturnsFalseAfterExpiry() {
    journal.create(KEY, Duration.ofMillis(1));
    await().atMost(Duration.ofSeconds(2)).until(() -> !journal.exists(KEY));
  }

  @Test
  void createMakesJournalAvailableForAppends() {
    journal.create(KEY, Duration.ofHours(1));

    String id = journal.append(KEY, "data".getBytes(UTF_8), Duration.ofHours(1));

    assertNotNull(id);
  }

  @Test
  void createThrowsOnDuplicateLiveJournal() {
    journal.create(KEY, Duration.ofHours(1));

    Duration oneHour = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.create(KEY, oneHour))
        .isInstanceOf(JournalAlreadyExistsException.class);
  }

  @Test
  void createSucceedsAfterDeadJournal() {
    journal.create(KEY, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertDoesNotThrow(() -> journal.create(KEY, Duration.ofHours(1))));
  }

  @Test
  void appendReturnsMonotonicallyIncreasingIds() {
    journal.create(KEY, Duration.ofHours(1));
    String id1 = journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    String id2 = journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));
    String id3 = journal.append(KEY, "data3".getBytes(UTF_8), Duration.ofHours(1));

    assertTrue(parseId(id1) < parseId(id2));
    assertTrue(parseId(id2) < parseId(id3));
  }

  @Test
  void appendResetsInactivityTimer() throws InterruptedException {
    journal.create(KEY, Duration.ofMillis(200));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    // Wait a bit, then append again to reset the timer
    new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
    journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));

    // Wait a bit more — journal should still be alive because append reset the timer
    new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
    assertDoesNotThrow(() -> journal.append(KEY, "data3".getBytes(UTF_8), Duration.ofHours(1)));
  }

  @Test
  void appendOnExpiredJournalThrowsJournalExpiredException() {
    journal.create(KEY, Duration.ofMillis(50));

    byte[] data = "data".getBytes(UTF_8);
    Duration oneHour = Duration.ofHours(1);
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.append(KEY, data, oneHour))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void appendOnNonExistentJournalThrowsJournalExpiredException() {
    byte[] data = "data".getBytes(UTF_8);
    Duration oneHour = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.append(KEY, data, oneHour))
        .isInstanceOf(JournalExpiredException.class);
  }

  @Test
  void appendOnCompletedJournalThrowsJournalCompletedException() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY, Duration.ofHours(1));

    byte[] data2 = "data2".getBytes(UTF_8);
    Duration oneHour = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.append(KEY, data2, oneHour))
        .isInstanceOf(JournalCompletedException.class);
  }

  @Test
  void appendAndReadAfterReturnsEntriesAfterCursor() {
    journal.create(KEY, Duration.ofHours(1));
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
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(KEY, "0-0");

    assertEquals(2, entries.size());
  }

  @Test
  void readAfterOnNonExistentKeyReturnsEmpty() {
    assertThat(journal.readAfter("nonexistent", "0-0")).isEmpty();
  }

  @Test
  void readLastReturnsLastNEntriesInOrder() {
    journal.create(KEY, Duration.ofHours(1));
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
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(KEY, "data2".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readLast(KEY, 10);

    assertEquals(2, entries.size());
  }

  @Test
  void readLastOnNonExistentKeyReturnsEmpty() {
    assertThat(journal.readLast("nonexistent", 5)).isEmpty();
  }

  @Test
  void evictionRemovesOldestEntriesWhenFull() {
    InMemoryJournalSpi smallJournal = new InMemoryJournalSpi(3);
    smallJournal.create(KEY, Duration.ofHours(1));

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
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    journal.delete(KEY);

    assertThatThrownBy(() -> journal.readAfter(KEY, "0-0"))
        .isInstanceOf(JournalExpiredException.class);
  }

  @Test
  void appendPreservesEntryFields() {
    journal.create(KEY, Duration.ofHours(1));
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
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(KEY, "0");

    assertEquals(1, entries.size());
  }

  @Test
  void separateKeysAreIndependent() {
    String key1 = "substrate:journal:one";
    String key2 = "substrate:journal:two";

    journal.create(key1, Duration.ofHours(1));
    journal.create(key2, Duration.ofHours(1));

    journal.append(key1, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.append(key2, "data2".getBytes(UTF_8), Duration.ofHours(1));

    assertEquals(1, journal.readAfter(key1, "0-0").size());
    assertEquals(1, journal.readAfter(key2, "0-0").size());

    journal.delete(key1);

    assertThatThrownBy(() -> journal.readAfter(key1, "0-0"))
        .isInstanceOf(JournalExpiredException.class);
    assertEquals(1, journal.readAfter(key2, "0-0").size());
  }

  @Test
  void completeBlocksFurtherAppends() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY, Duration.ofHours(1));

    byte[] data2 = "data2".getBytes(UTF_8);
    Duration oneHour = Duration.ofHours(1);
    assertThrows(JournalCompletedException.class, () -> journal.append(KEY, data2, oneHour));
  }

  @Test
  void completeUpdatesRetentionOnSecondCall() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY, Duration.ofMillis(50));

    // Update with longer retention — should still be readable
    journal.complete(KEY, Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(KEY, "0-0");
    assertThat(entries).hasSize(1);
  }

  @Test
  void completedJournalReadsStillWork() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY, Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(KEY, "0-0");
    assertThat(entries).hasSize(1);
  }

  @Test
  void completedJournalExpiresAfterRetentionTtl() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.readAfter(KEY, "0-0"))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void activeJournalExpiresViaInactivityTimeout() {
    journal.create(KEY, Duration.ofMillis(50));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    byte[] data2 = "data2".getBytes(UTF_8);
    Duration oneHour = Duration.ofHours(1);
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.append(KEY, data2, oneHour))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void completeOnExpiredJournalThrowsJournalExpiredException() {
    journal.create(KEY, Duration.ofMillis(50));

    Duration oneHour = Duration.ofHours(1);
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.complete(KEY, oneHour))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void deleteRemovesCompletedStatus() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY, Duration.ofHours(1));

    journal.delete(KEY);

    journal.create(KEY, Duration.ofHours(1));
    assertDoesNotThrow(() -> journal.append(KEY, "data3".getBytes(UTF_8), Duration.ofHours(1)));
  }

  @Test
  void isCompleteReturnsTrueForCompletedJournal() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY, Duration.ofHours(1));

    assertTrue(journal.isComplete(KEY));
  }

  @Test
  void isCompleteReturnsFalseForActiveJournal() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    assertFalse(journal.isComplete(KEY));
  }

  @Test
  void isCompleteReturnsFalseForExpiredJournal() {
    journal.create(KEY, Duration.ofMillis(50));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));
    journal.complete(KEY, Duration.ofMillis(50));

    await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertFalse(journal.isComplete(KEY)));
  }

  @Test
  void expiredEntriesAreExcludedFromReads() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "short-lived".getBytes(UTF_8), Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              List<RawJournalEntry> entries = journal.readAfter(KEY, "0-0");
              assertTrue(entries.isEmpty());
            });
  }

  @Test
  void sweepRemovesDeadJournals() {
    journal.create(KEY, Duration.ofMillis(50));
    journal.append(KEY, "data".getBytes(UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(journal.sweep(10)).isEqualTo(1));
  }

  @Test
  void sweepRespectsMaxToSweep() {
    String key1 = "substrate:journal:one";
    String key2 = "substrate:journal:two";
    journal.create(key1, Duration.ofMillis(50));
    journal.create(key2, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(journal.sweep(1)).isEqualTo(1));

    assertThat(journal.sweep(10)).isEqualTo(1);
  }

  @Test
  void sweepReturnsZeroWhenNothingExpired() {
    journal.create(KEY, Duration.ofHours(1));
    journal.append(KEY, "alive".getBytes(UTF_8), Duration.ofSeconds(10));

    assertThat(journal.sweep(1000)).isZero();
  }

  @Test
  void defaultConstructorCreatesUsableInstance() {
    InMemoryJournalSpi defaultJournal = new InMemoryJournalSpi();
    defaultJournal.create(KEY, Duration.ofHours(1));

    String id = defaultJournal.append(KEY, "data".getBytes(UTF_8), Duration.ofHours(1));

    assertNotNull(id);
    List<RawJournalEntry> entries = defaultJournal.readAfter(KEY, "0-0");
    assertEquals(1, entries.size());
  }

  @Test
  void readLastOnExpiredActiveJournalThrowsJournalExpiredException() {
    journal.create(KEY, Duration.ofMillis(50));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.readLast(KEY, 5))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void readAfterOnExpiredActiveJournalThrowsJournalExpiredException() {
    journal.create(KEY, Duration.ofMillis(50));
    journal.append(KEY, "data1".getBytes(UTF_8), Duration.ofHours(1));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> journal.readAfter(KEY, "0-0"))
                    .isInstanceOf(JournalExpiredException.class));
  }

  @Test
  void boundedEntryListRemoveExpiredRemovesExpiredEntries() {
    InMemoryJournalSpi.BoundedEntryList list = new InMemoryJournalSpi.BoundedEntryList(100);

    java.time.Instant now = java.time.Instant.now();
    java.time.Instant soonExpiry = now.plusSeconds(5);
    java.time.Instant farExpiry = now.plusSeconds(3600);

    list.add(new RawJournalEntry("1-0", KEY, "data".getBytes(UTF_8), now), soonExpiry);
    list.add(new RawJournalEntry("2-0", KEY, "live".getBytes(UTF_8), now), farExpiry);

    int removed = list.removeExpired(soonExpiry.plusSeconds(1), 10);

    assertEquals(1, removed);
    assertEquals(1, list.snapshot().size());
    assertArrayEquals("live".getBytes(UTF_8), list.snapshot().getFirst().entry().data());
  }

  @Test
  void boundedEntryListRemoveExpiredRespectsMaxLimit() {
    InMemoryJournalSpi.BoundedEntryList list = new InMemoryJournalSpi.BoundedEntryList(100);

    java.time.Instant now = java.time.Instant.now();
    java.time.Instant soonExpiry = now.plusSeconds(5);
    java.time.Instant farExpiry = now.plusSeconds(3600);

    list.add(new RawJournalEntry("1-0", KEY, "a".getBytes(UTF_8), now), soonExpiry);
    list.add(new RawJournalEntry("2-0", KEY, "b".getBytes(UTF_8), now), soonExpiry);
    list.add(new RawJournalEntry("3-0", KEY, "c".getBytes(UTF_8), now), farExpiry);

    int removed = list.removeExpired(soonExpiry.plusSeconds(1), 1);

    assertEquals(1, removed);
    assertEquals(2, list.snapshot().size());
  }

  private static long parseId(String id) {
    int dash = id.indexOf('-');
    return Long.parseLong(dash >= 0 ? id.substring(0, dash) : id);
  }
}
