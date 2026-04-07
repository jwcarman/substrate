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
package org.jwcarman.substrate.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;
import org.jwcarman.substrate.memory.InMemoryNotifier;

class JournalCursorTest {

  private static final Codec<String> STRING_CODEC =
      new Codec<>() {
        @Override
        public byte[] encode(String value) {
          return value.getBytes(UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
          return new String(bytes, UTF_8);
        }
      };

  private InMemoryJournalSpi journalSpi;
  private InMemoryNotifier notifier;
  private DefaultJournal<String> journal;

  @BeforeEach
  void setUp() {
    journalSpi = new InMemoryJournalSpi();
    notifier = new InMemoryNotifier();
    journal = new DefaultJournal<>(journalSpi, "substrate:journal:test", STRING_CODEC, notifier);
  }

  @Test
  void concurrentProducerAndCursorConsumer() {
    int count = 100;
    List<String> received = new CopyOnWriteArrayList<>();

    try (JournalCursor<String> cursor = journal.read()) {
      Thread.ofVirtual()
          .start(
              () -> {
                for (int i = 0; i < count; i++) {
                  journal.append("msg-" + i);
                }
                journal.complete();
              });

      while (cursor.isOpen()) {
        cursor.poll(Duration.ofSeconds(5)).ifPresent(entry -> received.add(entry.data()));
      }
    }

    assertThat(received).hasSize(count);
    for (int i = 0; i < count; i++) {
      assertThat(received.get(i)).isEqualTo("msg-" + i);
    }
  }

  @Test
  void completionCausesIsOpenToReturnFalse() {
    AtomicBoolean cursorClosed = new AtomicBoolean(false);

    try (JournalCursor<String> cursor = journal.read()) {
      Thread.ofVirtual()
          .start(
              () -> {
                journal.append("data");
                journal.complete();
              });

      while (cursor.isOpen()) {
        cursor.poll(Duration.ofSeconds(5));
      }
      cursorClosed.set(true);
    }

    assertThat(cursorClosed).isTrue();
  }

  @Test
  void closeInterruptsPollAndSetsIsOpenToFalse() throws InterruptedException {
    CountDownLatch pollStarted = new CountDownLatch(1);
    AtomicReference<Optional<JournalEntry<String>>> pollResult = new AtomicReference<>();

    JournalCursor<String> cursor = journal.read();

    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  pollStarted.countDown();
                  pollResult.set(cursor.poll(Duration.ofSeconds(30)));
                });

    assertThat(pollStarted.await(5, TimeUnit.SECONDS)).isTrue();
    // Give poll time to enter the wait
    Thread.sleep(100);

    cursor.close();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(cursor.isOpen()).isFalse();
              assertThat(pollResult.get()).isNotNull();
              assertThat(pollResult.get()).isEmpty();
            });
  }

  @Test
  void pollReturnsEmptyImmediatelyWhenCursorIsClosed() {
    JournalCursor<String> cursor = journal.read();
    cursor.close();

    assertThat(cursor.isOpen()).isFalse();
    assertThat(cursor.poll(Duration.ofSeconds(5))).isEmpty();
  }

  @Test
  void resumeWithReadAfterReceivesRemainingEntries() {
    String id1 = journal.append("first");
    journal.append("second");
    journal.append("third");

    List<String> received = new CopyOnWriteArrayList<>();
    try (JournalCursor<String> cursor = journal.readAfter(id1)) {
      // Poll entries available immediately
      Optional<JournalEntry<String>> entry;
      while ((entry = cursor.poll(Duration.ofMillis(200))).isPresent()) {
        received.add(entry.get().data());
      }
    }

    assertThat(received).containsExactly("second", "third");
  }

  @Test
  void readLastDeliversLastNEntriesThenContinuesLive() {
    journal.append("a");
    journal.append("b");
    journal.append("c");
    journal.append("d");
    journal.append("e");

    List<String> received = new CopyOnWriteArrayList<>();

    try (JournalCursor<String> cursor = journal.readLast(3)) {
      // Consume the replayed entries
      for (int i = 0; i < 3; i++) {
        cursor.poll(Duration.ofSeconds(1)).ifPresent(entry -> received.add(entry.data()));
      }

      assertThat(received).containsExactly("c", "d", "e");

      // Now append a live entry and verify the cursor picks it up
      journal.append("f");

      Optional<JournalEntry<String>> liveEntry = cursor.poll(Duration.ofSeconds(5));
      assertThat(liveEntry).isPresent();
      assertThat(liveEntry.get().data()).isEqualTo("f");
    }
  }

  @Test
  void pollReturnsEmptyOnTimeout() {
    try (JournalCursor<String> cursor = journal.read()) {
      Optional<JournalEntry<String>> result = cursor.poll(Duration.ofMillis(50));
      assertThat(result).isEmpty();
      assertThat(cursor.isOpen()).isTrue();
    }
  }

  @Test
  void lastIdReturnsLastConsumedEntryId() {
    String id1 = journal.append("first");
    String id2 = journal.append("second");

    try (JournalCursor<String> cursor = journal.readAfter(id1)) {
      assertThat(cursor.lastId()).isEqualTo(id1);

      cursor.poll(Duration.ofSeconds(1));

      assertThat(cursor.lastId()).isEqualTo(id2);
    }
  }

  @Test
  void readFromTailDoesNotReceiveExistingEntries() {
    journal.append("existing-1");
    journal.append("existing-2");

    try (JournalCursor<String> cursor = journal.read()) {
      Optional<JournalEntry<String>> result = cursor.poll(Duration.ofMillis(200));
      assertThat(result).isEmpty();

      journal.append("new-entry");

      result = cursor.poll(Duration.ofSeconds(5));
      assertThat(result).isPresent();
      assertThat(result.get().data()).isEqualTo("new-entry");
    }
  }

  @Test
  void stackedNudgesAreBatchedEfficiently() {
    // Rapidly append many entries — reader should batch them in a single SPI read
    int count = 50;
    try (JournalCursor<String> cursor = journal.read()) {
      for (int i = 0; i < count; i++) {
        journal.append("rapid-" + i);
      }

      List<String> received = new CopyOnWriteArrayList<>();
      for (int i = 0; i < count; i++) {
        cursor.poll(Duration.ofSeconds(5)).ifPresent(entry -> received.add(entry.data()));
      }

      assertThat(received).hasSize(count);
      for (int i = 0; i < count; i++) {
        assertThat(received.get(i)).isEqualTo("rapid-" + i);
      }
    }
  }

  @Test
  void missedNudgeDataIsFoundByJustInCaseRead() {
    // Append data before creating the cursor — the reader thread's "just in case"
    // read on startup should find it without a nudge ever being received
    journal.append("before-cursor");

    try (JournalCursor<String> cursor = journal.readLast(Integer.MAX_VALUE)) {
      Optional<JournalEntry<String>> entry = cursor.poll(Duration.ofSeconds(5));
      assertThat(entry).isPresent();
      assertThat(entry.get().data()).isEqualTo("before-cursor");
    }
  }

  @Test
  void backpressureSlowConsumerReceivesAllEntries() throws InterruptedException {
    int count = 200;
    List<String> received = new CopyOnWriteArrayList<>();

    try (JournalCursor<String> cursor = journal.read()) {
      // Producer appends rapidly
      Thread producer =
          Thread.ofVirtual()
              .start(
                  () -> {
                    for (int i = 0; i < count; i++) {
                      journal.append("bp-" + i);
                    }
                    journal.complete();
                  });

      // Slow consumer — small delay between polls to exercise backpressure
      while (cursor.isOpen()) {
        cursor.poll(Duration.ofSeconds(5)).ifPresent(entry -> received.add(entry.data()));
        Thread.sleep(1);
      }

      producer.join(10000);
    }

    assertThat(received).hasSize(count);
    for (int i = 0; i < count; i++) {
      assertThat(received.get(i)).isEqualTo("bp-" + i);
    }
  }

  @Test
  void tryWithResourcesCleanupWorks() {
    JournalCursor<String> cursor;
    try (JournalCursor<String> c = journal.read()) {
      cursor = c;
      assertThat(cursor.isOpen()).isTrue();
    }
    assertThat(cursor.isOpen()).isFalse();
  }
}
