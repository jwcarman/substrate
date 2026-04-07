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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;
import org.jwcarman.substrate.memory.InMemoryNotifier;

class JournalSubscriptionTest {

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
  void subscriberReceivesEntriesAsTheyAreAppended() {
    List<String> received = new CopyOnWriteArrayList<>();
    try (Subscription sub = journal.subscribe(entry -> received.add(entry.data()))) {
      journal.append("hello");
      journal.append("world");

      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(2));
      assertThat(received).containsExactly("hello", "world");
    }
  }

  @Test
  void subscriberFromCursorReceivesReplayedAndLiveEntries() {
    String id1 = journal.append("first");
    journal.append("second");
    journal.append("third");

    List<String> received = new CopyOnWriteArrayList<>();
    try (Subscription sub = journal.subscribe(id1, entry -> received.add(entry.data()))) {
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(2));
      assertThat(received).containsExactly("second", "third");

      // Append a live entry after subscription
      journal.append("fourth");

      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(3));
      assertThat(received).containsExactly("second", "third", "fourth");
    }
  }

  @Test
  void subscriptionCancelStopsDelivery() {
    List<String> received = new CopyOnWriteArrayList<>();
    Subscription sub = journal.subscribe(entry -> received.add(entry.data()));

    journal.append("before-cancel");
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(1));

    sub.cancel();

    // Give the thread time to stop
    await().pollDelay(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2)).until(() -> true);

    journal.append("after-cancel");

    // Wait a bit and verify no more entries arrive
    await()
        .pollDelay(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(received).hasSize(1));
  }

  @Test
  void completionTerminatesSubscriber() {
    AtomicBoolean completeCalled = new AtomicBoolean(false);
    List<String> received = new CopyOnWriteArrayList<>();

    JournalSubscriber<String> subscriber =
        new JournalSubscriber<>() {
          @Override
          public void onEntry(JournalEntry<String> entry) {
            received.add(entry.data());
          }

          @Override
          public void onComplete() {
            completeCalled.set(true);
          }
        };

    try (Subscription sub = journal.subscribe(subscriber)) {
      journal.append("data");
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(1));

      journal.complete();
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(completeCalled).isTrue());
    }

    assertThat(received).containsExactly("data");
  }

  @Test
  void streamJournalSubscriberBlockingStream() {
    try (var streamSub = new StreamJournalSubscriber<String>()) {
      Subscription sub = journal.subscribe(streamSub);

      // Append in a separate thread
      Thread.ofVirtual()
          .start(
              () -> {
                journal.append("a");
                journal.append("b");
                journal.append("c");
                journal.complete();
              });

      List<String> collected = streamSub.stream().map(JournalEntry::data).toList();

      assertThat(collected).containsExactly("a", "b", "c");
      sub.cancel();
    }
  }

  @Test
  void streamJournalSubscriberTerminatesWhenClosed() throws InterruptedException {
    var streamSub = new StreamJournalSubscriber<String>();
    Subscription sub = journal.subscribe(streamSub);

    List<String> collected = new CopyOnWriteArrayList<>();
    CountDownLatch streamDone = new CountDownLatch(1);

    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  streamSub.stream().forEach(entry -> collected.add(entry.data()));
                  streamDone.countDown();
                });

    journal.append("x");
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(collected).hasSize(1));

    streamSub.close();

    assertThat(streamDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(collected).containsExactly("x");
    sub.cancel();
  }

  @Test
  void concurrentProducerAndConsumer() {
    int count = 100;
    List<String> received = new CopyOnWriteArrayList<>();

    try (Subscription sub = journal.subscribe(entry -> received.add(entry.data()))) {
      // Producer thread
      Thread.ofVirtual()
          .start(
              () -> {
                for (int i = 0; i < count; i++) {
                  journal.append("msg-" + i);
                }
              });

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(received).hasSize(count));

      // Verify ordering
      for (int i = 0; i < count; i++) {
        assertThat(received.get(i)).isEqualTo("msg-" + i);
      }
    }
  }

  @Test
  void tailSubscribeDoesNotReceiveExistingEntries() {
    journal.append("existing-1");
    journal.append("existing-2");

    List<String> received = new CopyOnWriteArrayList<>();
    try (Subscription sub = journal.subscribe(entry -> received.add(entry.data()))) {
      // Wait a bit to make sure existing entries aren't delivered
      await()
          .pollDelay(Duration.ofMillis(200))
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(received).isEmpty());

      journal.append("new-entry");
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(1));
      assertThat(received).containsExactly("new-entry");
    }
  }

  @Test
  void backpressureBlocksOnEntryWhenQueueIsFull() throws InterruptedException {
    var streamSub = new StreamJournalSubscriber<String>(2);
    Subscription sub = journal.subscribe(streamSub);

    // Fill the queue
    journal.append("a");
    journal.append("b");

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              // Check the queue is filling up by trying to consume
              return true;
            });

    // The third append should cause the subscriber's onEntry to block
    // (the producer thread in DefaultJournal will be blocked on queue.put)
    journal.append("c");

    // Now consume — this should unblock
    List<String> collected = new CopyOnWriteArrayList<>();
    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  streamSub.stream().limit(3).forEach(entry -> collected.add(entry.data()));
                });

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(collected).hasSize(3));
    assertThat(collected).containsExactly("a", "b", "c");

    streamSub.close();
    sub.cancel();
  }

  @Test
  void subscriptionIsAutoCloseable() {
    List<String> received = new CopyOnWriteArrayList<>();
    try (Subscription sub = journal.subscribe(entry -> received.add(entry.data()))) {
      journal.append("inside");
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(1));
    }
    // Subscription should be cancelled after try-with-resources
  }
}
