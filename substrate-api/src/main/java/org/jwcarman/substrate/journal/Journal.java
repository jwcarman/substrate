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
package org.jwcarman.substrate.journal;

import java.time.Duration;
import java.util.function.Consumer;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.Subscriber;
import org.jwcarman.substrate.SubscriberConfig;
import org.jwcarman.substrate.Subscription;

/**
 * A distributed append-only event stream with per-entry TTLs, a completion lifecycle, and monotonic
 * ordering.
 *
 * <p>Each journal is identified by a unique key and supports multiple concurrent producers and
 * consumers. Entries are appended with individual time-to-live durations and are assigned
 * monotonically increasing IDs that can be used as resume checkpoints.
 *
 * <p><strong>Inactivity TTL.</strong> A journal is created with an inactivity TTL (see {@link
 * JournalFactory#create(String, Class, Duration)}). If no entries are appended within that duration
 * the journal auto-expires and subsequent operations throw {@link JournalExpiredException}. Each
 * successful {@code append} resets the inactivity timer.
 *
 * <p><strong>Completion and retention.</strong> Calling {@link #complete(Duration)} marks the
 * journal as completed. No further appends are accepted (they throw {@link
 * JournalCompletedException}), but existing entries remain readable for the specified retention
 * period. Once the retention TTL elapses the journal expires fully.
 *
 * <p>All methods are thread-safe.
 *
 * <h2>Usage example</h2>
 *
 * <pre>{@code
 * // Producer
 * Journal<String> journal = factory.create("events", String.class, Duration.ofMinutes(30));
 * journal.append("event-1", Duration.ofHours(1));
 * journal.append("event-2", Duration.ofHours(1));
 * journal.complete(Duration.ofMinutes(5));
 *
 * // Consumer
 * BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
 * while (sub.isActive()) {
 *     NextResult<JournalEntry<String>> result = sub.next(Duration.ofSeconds(5));
 *     switch (result) {
 *         case NextResult.Value<JournalEntry<String>> v -> process(v.value());
 *         case NextResult.Timeout<?> t -> { /* poll again *​/ }
 *         case NextResult.Completed<?> c -> log.info("journal completed");
 *         case NextResult.Expired<?> e -> log.warn("journal expired");
 *         case NextResult.Deleted<?> d -> log.warn("journal deleted");
 *         case NextResult.Errored<?> err -> log.error("error", err.cause());
 *     }
 * }
 * }</pre>
 *
 * @param <T> the entry payload type
 * @see JournalFactory
 * @see JournalEntry
 * @see BlockingSubscription
 */
public interface Journal<T> {

  /**
   * Appends an entry to this journal with the given time-to-live.
   *
   * <p>Each successful append resets the journal's inactivity timer.
   *
   * @param data the entry payload
   * @param ttl time-to-live for this individual entry
   * @return the generated entry ID, monotonically ordered with respect to other entries in this
   *     journal
   * @throws JournalCompletedException if this journal has already been completed
   * @throws JournalExpiredException if this journal has expired or been deleted
   */
  String append(T data, Duration ttl);

  /**
   * Marks this journal as completed with the given retention TTL.
   *
   * <p>After completion no further entries may be appended. Existing entries and active
   * subscriptions remain readable until the retention period elapses, at which point the journal
   * expires fully.
   *
   * @param retentionTtl how long the journal remains readable after completion
   */
  void complete(Duration retentionTtl);

  /**
   * Explicitly deletes this journal and all of its entries.
   *
   * <p>Active subscriptions receive a {@link org.jwcarman.substrate.NextResult.Deleted} result.
   */
  void delete();

  /**
   * Subscribe from the current tail. Only entries appended after this call are delivered.
   * Historical entries (those already in the journal when {@code subscribe} is called) are NOT
   * replayed.
   *
   * @return a blocking subscription that delivers future entries
   */
  BlockingSubscription<JournalEntry<T>> subscribe();

  /**
   * Subscribe starting strictly after {@code afterId}. All entries with an id greater than {@code
   * afterId} are delivered, followed by new entries as they arrive. Useful for resuming from a
   * persisted checkpoint.
   *
   * @param afterId the entry ID to resume after (exclusive)
   * @return a blocking subscription starting after the given entry
   */
  BlockingSubscription<JournalEntry<T>> subscribeAfter(String afterId);

  /**
   * Subscribe starting with the last {@code count} retained entries, then continue with new entries
   * as they arrive. Useful for "show the last N events, then tail" patterns.
   *
   * @param count the number of recent entries to replay before tailing
   * @return a blocking subscription starting from the last {@code count} entries
   */
  BlockingSubscription<JournalEntry<T>> subscribeLast(int count);

  /** Callback subscribe from current tail with a ready-made {@link Subscriber}. */
  Subscription subscribe(Subscriber<JournalEntry<T>> subscriber);

  /** Callback subscribe from current tail with a {@link SubscriberConfig} customizer. */
  Subscription subscribe(Consumer<SubscriberConfig<JournalEntry<T>>> customizer);

  /** Callback subscribe from a checkpoint with a ready-made {@link Subscriber}. */
  Subscription subscribeAfter(String afterId, Subscriber<JournalEntry<T>> subscriber);

  /** Callback subscribe from a checkpoint with a {@link SubscriberConfig} customizer. */
  Subscription subscribeAfter(
      String afterId, Consumer<SubscriberConfig<JournalEntry<T>>> customizer);

  /** Callback subscribe from last N entries with a ready-made {@link Subscriber}. */
  Subscription subscribeLast(int count, Subscriber<JournalEntry<T>> subscriber);

  /** Callback subscribe from last N entries with a {@link SubscriberConfig} customizer. */
  Subscription subscribeLast(int count, Consumer<SubscriberConfig<JournalEntry<T>>> customizer);

  /**
   * Returns the backend key that uniquely identifies this journal.
   *
   * @return the journal key
   */
  String key();
}
