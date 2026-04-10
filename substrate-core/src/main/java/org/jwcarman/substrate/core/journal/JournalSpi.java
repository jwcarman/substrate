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
import org.jwcarman.substrate.journal.JournalAlreadyExistsException;
import org.jwcarman.substrate.journal.JournalCompletedException;
import org.jwcarman.substrate.journal.JournalExpiredException;

public interface JournalSpi {

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

  /**
   * Delete up to {@code maxToSweep} expired records from the backend.
   *
   * <p>Implementations that rely on native backend TTL (Redis EXPIRE, DynamoDB TTL, Mongo TTL
   * indexes, etc.) should leave this method as the default no-op inherited from {@link
   * AbstractJournalSpi}.
   *
   * <p>Implementations that do not have native TTL support (e.g., the Postgres backend) must
   * override this with a batched physical delete of expired records.
   *
   * <p>The sweeper thread in substrate-core calls this method on a fixed schedule and drains in a
   * loop: when the returned count equals {@code maxToSweep}, it immediately calls again to keep
   * draining; when the returned count is less than {@code maxToSweep}, it stops draining and sleeps
   * until the next scheduled tick.
   *
   * <p>Implementations must be safe to call concurrently from multiple nodes in a multi-instance
   * deployment. For database backends, use the canonical "concurrent workers" pattern for your
   * engine — e.g., {@code DELETE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)} for Postgres.
   *
   * @param maxToSweep the maximum number of records to delete in this call; must be positive
   * @return the actual number of records deleted — zero if none were found, exactly {@code
   *     maxToSweep} if more likely remain, something in between otherwise
   */
  int sweep(int maxToSweep);

  String journalKey(String name);
}
