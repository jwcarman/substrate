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
package org.jwcarman.substrate.core.sweep;

/**
 * A backend capability: can physically remove expired records on demand. Implemented by SPI
 * interfaces for primitives that carry expirable state ({@link
 * org.jwcarman.substrate.core.atom.AtomSpi}, {@link
 * org.jwcarman.substrate.core.journal.JournalSpi}, {@link
 * org.jwcarman.substrate.core.mailbox.MailboxSpi}).
 *
 * <p>The {@link Sweeper} driver class calls {@link #sweep(int)} on a scheduled cadence and uses the
 * returned count to decide whether to keep draining in this tick or wait until the next scheduled
 * call.
 *
 * <p>Backends with native TTL (Redis EXPIRE, DynamoDB TTL, Mongo TTL indexes, etc.) inherit a no-op
 * default from their corresponding {@code AbstractXSpi} base class and need not override this
 * method. Backends without native TTL (e.g., PostgreSQL) must override with a batched conditional
 * delete — see the SPI javadoc on the specific primitive's interface for the full contract.
 *
 * @see Sweeper
 */
public interface Sweepable {

  /**
   * Delete up to {@code maxToSweep} expired records from the backend.
   *
   * <p>Return exactly 0 if nothing was found, exactly {@code maxToSweep} if the caller should drain
   * again immediately, or something in between if there is no more work for this tick.
   *
   * <p>Must be safe to call concurrently from multiple processes in a clustered deployment. For
   * database backends, use the canonical "concurrent workers" pattern for your engine (e.g., {@code
   * SELECT ... FOR UPDATE SKIP LOCKED} on Postgres).
   *
   * @param maxToSweep maximum number of records to delete in this call; must be positive
   * @return the actual number of records deleted
   */
  int sweep(int maxToSweep);
}
