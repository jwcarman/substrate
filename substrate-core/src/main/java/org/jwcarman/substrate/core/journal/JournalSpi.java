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

import java.time.Duration;
import java.util.List;
import org.jwcarman.substrate.core.sweep.Sweepable;
import org.jwcarman.substrate.journal.JournalAlreadyExistsException;
import org.jwcarman.substrate.journal.JournalCompletedException;
import org.jwcarman.substrate.journal.JournalExpiredException;

/**
 * Backend SPI for Journal storage operations.
 *
 * <p>Implementations must provide ordered append, cursor-based reads, completion lifecycle
 * management, and expiry semantics. The {@link #append append} and {@link #readAfter readAfter}
 * methods may be called concurrently from multiple threads. {@link
 * org.jwcarman.substrate.core.sweep.Sweepable#sweep sweep} may run concurrently with {@link #append
 * append}.
 *
 * @see AbstractJournalSpi
 * @see RawJournalEntry
 */
public interface JournalSpi extends Sweepable {

  /**
   * Create a new journal with an inactivity TTL. Must be atomic set-if-not-exists.
   *
   * @throws JournalAlreadyExistsException if a live journal already exists at this key
   */
  void create(String key, Duration inactivityTtl);

  /**
   * Append an entry, resetting the journal's inactivity timer atomically.
   *
   * @throws JournalCompletedException if the journal is completed
   * @throws JournalExpiredException if the journal is expired
   */
  String append(String key, byte[] data, Duration entryTtl);

  /**
   * Read entries strictly after the given id.
   *
   * @throws JournalExpiredException if the journal is expired
   */
  List<RawJournalEntry> readAfter(String key, String afterId);

  /**
   * Read the last {@code count} entries in chronological order.
   *
   * @throws JournalExpiredException if the journal is expired
   */
  List<RawJournalEntry> readLast(String key, int count);

  /**
   * Mark the journal as completed with a retention TTL. Calling on an already-completed journal
   * updates the retention TTL (latest call wins).
   *
   * @throws JournalExpiredException if the journal is already expired
   */
  void complete(String key, Duration retentionTtl);

  /**
   * Removes the journal and all of its entries. This operation is idempotent — deleting a
   * non-existent journal is a no-op.
   *
   * @param key the backend storage key for the journal
   */
  void delete(String key);

  /**
   * Returns whether a live journal exists at the given key.
   *
   * <p>A journal is "live" if it was created and has not expired. Completed journals within their
   * retention window still count as existing — they can be attached to and read, even though they
   * no longer accept appends.
   *
   * @param key the backend storage key
   * @return {@code true} if a live journal exists at this key
   */
  boolean exists(String key);

  /**
   * Returns whether the journal has been marked as completed.
   *
   * @param key the backend storage key for the journal
   * @return {@code true} if the journal has been completed; {@code false} otherwise
   */
  boolean isComplete(String key);

  /**
   * Builds a backend storage key from a logical journal name.
   *
   * @param name the logical journal name
   * @return the fully-qualified backend key
   */
  String journalKey(String name);
}
