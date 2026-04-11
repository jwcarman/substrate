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
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;

public interface Journal<T> {

  String append(T data, Duration ttl);

  void complete(Duration retentionTtl);

  void delete();

  /**
   * Subscribe from the current tail. Only entries appended after this call are delivered.
   * Historical entries (those already in the journal when {@code subscribe} is called) are NOT
   * replayed.
   */
  BlockingSubscription<JournalEntry<T>> subscribe();

  /**
   * Subscribe starting strictly after {@code afterId}. All entries with an id greater than {@code
   * afterId} are delivered, followed by new entries as they arrive. Useful for resuming from a
   * persisted checkpoint.
   */
  BlockingSubscription<JournalEntry<T>> subscribeAfter(String afterId);

  /**
   * Subscribe starting with the last {@code count} retained entries, then continue with new entries
   * as they arrive. Useful for "show the last N events, then tail" patterns.
   */
  BlockingSubscription<JournalEntry<T>> subscribeLast(int count);

  /** Callback subscribe from current tail with only onNext. */
  CallbackSubscription subscribe(Consumer<JournalEntry<T>> onNext);

  /** Callback subscribe from current tail with additional handlers. */
  CallbackSubscription subscribe(
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer);

  /** Callback subscribe from a checkpoint with only onNext. */
  CallbackSubscription subscribeAfter(String afterId, Consumer<JournalEntry<T>> onNext);

  /** Callback subscribe from a checkpoint with additional handlers. */
  CallbackSubscription subscribeAfter(
      String afterId,
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer);

  /** Callback subscribe from last N entries with only onNext. */
  CallbackSubscription subscribeLast(int count, Consumer<JournalEntry<T>> onNext);

  /** Callback subscribe from last N entries with additional handlers. */
  CallbackSubscription subscribeLast(
      int count,
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer);

  String key();
}
