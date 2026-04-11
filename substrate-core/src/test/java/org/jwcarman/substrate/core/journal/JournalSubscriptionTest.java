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
package org.jwcarman.substrate.core.journal;

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
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.journal.JournalEntry;

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
    journalSpi.create("substrate:journal:test", Duration.ofHours(24));
    journal =
        new DefaultJournal<>(
            journalSpi,
            "substrate:journal:test",
            STRING_CODEC,
            notifier,
            1024,
            Duration.ofDays(7),
            Duration.ofDays(30));
  }

  @Test
  void concurrentProducerAndSubscriptionConsumer() {
    int count = 100;
    List<String> received = new CopyOnWriteArrayList<>();

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      Thread.ofVirtual()
          .start(
              () -> {
                for (int i = 0; i < count; i++) {
                  journal.append("msg-" + i, Duration.ofHours(1));
                }
                journal.complete(Duration.ofDays(1));
              });

      loop:
      while (sub.isActive()) {
        switch (sub.next(Duration.ofSeconds(5))) {
          case NextResult.Value<JournalEntry<String>>(var entry) -> received.add(entry.data());
          case NextResult.Completed<JournalEntry<String>> _ -> {
            break loop;
          }
          default -> {
            /* not relevant to this test */
          }
        }
      }
    } finally {
      sub.cancel();
    }

    assertThat(received).hasSize(count);
    for (int i = 0; i < count; i++) {
      assertThat(received.get(i)).isEqualTo("msg-" + i);
    }
  }

  @Test
  void completionCausesSubscriptionToEnd() {
    AtomicBoolean subscriptionEnded = new AtomicBoolean(false);

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      Thread.ofVirtual()
          .start(
              () -> {
                journal.append("data", Duration.ofHours(1));
                journal.complete(Duration.ofDays(1));
              });

      loop:
      while (sub.isActive()) {
        switch (sub.next(Duration.ofSeconds(5))) {
          case NextResult.Completed<JournalEntry<String>> _ -> {
            subscriptionEnded.set(true);
            break loop;
          }
          default -> {
            /* not relevant to this test */
          }
        }
      }
    } finally {
      sub.cancel();
    }

    assertThat(subscriptionEnded).isTrue();
  }

  @Test
  void nextReturnsTimeoutAfterCancel() {
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    sub.cancel();

    NextResult<JournalEntry<String>> result = sub.next(Duration.ofMillis(50));
    assertThat(result).isInstanceOf(NextResult.Timeout.class);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void subscribeAfterReceivesRemainingEntries() {
    String id1 = journal.append("first", Duration.ofHours(1));
    journal.append("second", Duration.ofHours(1));
    journal.append("third", Duration.ofHours(1));

    List<String> received = new CopyOnWriteArrayList<>();
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeAfter(id1);
    try {
      for (int i = 0; i < 2; i++) {
        NextResult<JournalEntry<String>> result = sub.next(Duration.ofSeconds(5));
        if (result instanceof NextResult.Value<JournalEntry<String>>(var entry)) {
          received.add(entry.data());
        }
      }
    } finally {
      sub.cancel();
    }

    assertThat(received).containsExactly("second", "third");
  }

  @Test
  void subscribeLastDeliversLastNEntriesThenContinuesLive() {
    journal.append("a", Duration.ofHours(1));
    journal.append("b", Duration.ofHours(1));
    journal.append("c", Duration.ofHours(1));
    journal.append("d", Duration.ofHours(1));
    journal.append("e", Duration.ofHours(1));

    List<String> received = new CopyOnWriteArrayList<>();

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeLast(3);
    try {
      for (int i = 0; i < 3; i++) {
        NextResult<JournalEntry<String>> result = sub.next(Duration.ofSeconds(1));
        if (result instanceof NextResult.Value<JournalEntry<String>>(var entry)) {
          received.add(entry.data());
        }
      }

      assertThat(received).containsExactly("c", "d", "e");

      journal.append("f", Duration.ofHours(1));

      NextResult<JournalEntry<String>> liveResult = sub.next(Duration.ofSeconds(5));
      assertThat(liveResult).isInstanceOf(NextResult.Value.class);
      assertThat(((NextResult.Value<JournalEntry<String>>) liveResult).value().data())
          .isEqualTo("f");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void nextReturnsTimeoutWhenNoData() {
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      NextResult<JournalEntry<String>> result = sub.next(Duration.ofMillis(50));
      assertThat(result).isInstanceOf(NextResult.Timeout.class);
      assertThat(sub.isActive()).isTrue();
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeFromTailDoesNotReceiveExistingEntries() {
    journal.append("existing-1", Duration.ofHours(1));
    journal.append("existing-2", Duration.ofHours(1));

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      NextResult<JournalEntry<String>> result = sub.next(Duration.ofMillis(200));
      assertThat(result).isInstanceOf(NextResult.Timeout.class);

      journal.append("new-entry", Duration.ofHours(1));

      result = sub.next(Duration.ofSeconds(5));
      assertThat(result).isInstanceOf(NextResult.Value.class);
      assertThat(((NextResult.Value<JournalEntry<String>>) result).value().data())
          .isEqualTo("new-entry");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void stackedNudgesAreBatchedEfficiently() {
    int count = 50;
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      for (int i = 0; i < count; i++) {
        journal.append("rapid-" + i, Duration.ofHours(1));
      }

      List<String> received = new CopyOnWriteArrayList<>();
      for (int i = 0; i < count; i++) {
        NextResult<JournalEntry<String>> result = sub.next(Duration.ofSeconds(5));
        if (result instanceof NextResult.Value<JournalEntry<String>>(var entry)) {
          received.add(entry.data());
        }
      }

      assertThat(received).hasSize(count);
      for (int i = 0; i < count; i++) {
        assertThat(received.get(i)).isEqualTo("rapid-" + i);
      }
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeLastFindsPreExistingEntries() {
    journal.append("before-sub", Duration.ofHours(1));

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeLast(Integer.MAX_VALUE);
    try {
      NextResult<JournalEntry<String>> result = sub.next(Duration.ofSeconds(5));
      assertThat(result).isInstanceOf(NextResult.Value.class);
      assertThat(((NextResult.Value<JournalEntry<String>>) result).value().data())
          .isEqualTo("before-sub");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void backpressureSlowConsumerReceivesAllEntries() throws InterruptedException {
    int count = 200;
    List<String> received = new CopyOnWriteArrayList<>();

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      Thread producer =
          Thread.ofVirtual()
              .start(
                  () -> {
                    for (int i = 0; i < count; i++) {
                      journal.append("bp-" + i, Duration.ofHours(1));
                    }
                    journal.complete(Duration.ofDays(1));
                  });

      loop:
      while (sub.isActive()) {
        switch (sub.next(Duration.ofSeconds(5))) {
          case NextResult.Value<JournalEntry<String>>(var entry) -> {
            received.add(entry.data());
            new CountDownLatch(1).await(1, TimeUnit.MILLISECONDS);
          }
          case NextResult.Completed<JournalEntry<String>> _ -> {
            break loop;
          }
          default -> {
            /* not relevant to this test */
          }
        }
      }

      producer.join(10000);
    } finally {
      sub.cancel();
    }

    assertThat(received).hasSize(count);
    for (int i = 0; i < count; i++) {
      assertThat(received.get(i)).isEqualTo("bp-" + i);
    }
  }

  @Test
  void cancelSetsIsActiveToFalse() {
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    assertThat(sub.isActive()).isTrue();
    sub.cancel();
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void subscribeLastWithMoreThanAvailableReturnsAll() {
    journal.append("one", Duration.ofHours(1));
    journal.append("two", Duration.ofHours(1));

    List<String> received = new CopyOnWriteArrayList<>();
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeLast(100);
    try {
      for (int i = 0; i < 2; i++) {
        NextResult<JournalEntry<String>> result = sub.next(Duration.ofSeconds(5));
        if (result instanceof NextResult.Value<JournalEntry<String>>(var entry)) {
          received.add(entry.data());
        }
      }
    } finally {
      sub.cancel();
    }

    assertThat(received).containsExactly("one", "two");
  }

  @Test
  void deleteNotifiesSubscription() {
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      Thread.ofVirtual().start(() -> journal.delete());

      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                NextResult<JournalEntry<String>> result = sub.next(Duration.ofMillis(100));
                assertThat(result).isInstanceOf(NextResult.Deleted.class);
              });
    } finally {
      sub.cancel();
    }
  }

  @Test
  void completionDrainsAllEntriesBeforeCompleted() {
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      for (int i = 0; i < 5; i++) {
        journal.append("entry-" + i, Duration.ofHours(1));
      }
      journal.complete(Duration.ofDays(1));

      List<String> received = new CopyOnWriteArrayList<>();
      loop:
      while (sub.isActive()) {
        switch (sub.next(Duration.ofSeconds(5))) {
          case NextResult.Value<JournalEntry<String>>(var entry) -> received.add(entry.data());
          case NextResult.Completed<JournalEntry<String>> _ -> {
            break loop;
          }
          default -> {
            /* not relevant to this test */
          }
        }
      }

      assertThat(received).hasSize(5);
      for (int i = 0; i < 5; i++) {
        assertThat(received.get(i)).isEqualTo("entry-" + i);
      }
    } finally {
      sub.cancel();
    }
  }

  @Test
  void callbackSubscribeDeliversEntries() throws InterruptedException {
    List<String> received = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(3);

    CallbackSubscription sub =
        journal.subscribe(
            entry -> {
              received.add(entry.data());
              latch.countDown();
            });
    try {
      journal.append("a", Duration.ofHours(1));
      journal.append("b", Duration.ofHours(1));
      journal.append("c", Duration.ofHours(1));

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(received).containsExactly("a", "b", "c");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void callbackSubscribeWithCustomizerHandlesCompletion() throws InterruptedException {
    AtomicBoolean completed = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);

    CallbackSubscription sub =
        journal.subscribe(
            entry -> {},
            b ->
                b.onComplete(
                    () -> {
                      completed.set(true);
                      latch.countDown();
                    }));
    try {
      journal.complete(Duration.ofDays(1));

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(completed.get()).isTrue();
    } finally {
      sub.cancel();
    }
  }

  @Test
  void callbackSubscribeAfterDeliversFromCheckpoint() throws InterruptedException {
    String id1 = journal.append("first", Duration.ofHours(1));
    journal.append("second", Duration.ofHours(1));
    journal.append("third", Duration.ofHours(1));

    List<String> received = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(2);

    CallbackSubscription sub =
        journal.subscribeAfter(
            id1,
            entry -> {
              received.add(entry.data());
              latch.countDown();
            });
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(received).containsExactly("second", "third");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void callbackSubscribeLastDeliversHistoricalThenLive() throws InterruptedException {
    journal.append("a", Duration.ofHours(1));
    journal.append("b", Duration.ofHours(1));
    journal.append("c", Duration.ofHours(1));

    List<String> received = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(4);

    CallbackSubscription sub =
        journal.subscribeLast(
            2,
            entry -> {
              received.add(entry.data());
              latch.countDown();
            });
    try {
      journal.append("d", Duration.ofHours(1));
      journal.append("e", Duration.ofHours(1));

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(received).startsWith("b", "c");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void checkpointAdvancesMonotonically() {
    DefaultJournal<String> smallJournal =
        new DefaultJournal<>(
            journalSpi,
            "substrate:journal:test",
            STRING_CODEC,
            notifier,
            1,
            Duration.ofDays(7),
            Duration.ofDays(30));

    journal.append("e1", Duration.ofHours(1));
    journal.append("e2", Duration.ofHours(1));
    journal.append("e3", Duration.ofHours(1));

    BlockingSubscription<JournalEntry<String>> sub = smallJournal.subscribeLast(3);
    try {
      NextResult<JournalEntry<String>> r1 = sub.next(Duration.ofSeconds(5));
      assertThat(r1).isInstanceOf(NextResult.Value.class);
      assertThat(((NextResult.Value<JournalEntry<String>>) r1).value().data()).isEqualTo("e1");

      NextResult<JournalEntry<String>> r2 = sub.next(Duration.ofSeconds(5));
      assertThat(r2).isInstanceOf(NextResult.Value.class);
      assertThat(((NextResult.Value<JournalEntry<String>>) r2).value().data()).isEqualTo("e2");

      NextResult<JournalEntry<String>> r3 = sub.next(Duration.ofSeconds(5));
      assertThat(r3).isInstanceOf(NextResult.Value.class);
      assertThat(((NextResult.Value<JournalEntry<String>>) r3).value().data()).isEqualTo("e3");
    } finally {
      sub.cancel();
    }
  }
}
