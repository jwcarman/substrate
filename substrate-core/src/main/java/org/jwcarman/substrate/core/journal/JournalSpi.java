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

  void delete(String key);

  boolean isComplete(String key);

  String journalKey(String name);
}
