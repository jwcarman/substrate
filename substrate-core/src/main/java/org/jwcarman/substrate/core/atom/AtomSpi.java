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
package org.jwcarman.substrate.core.atom;

import java.time.Duration;
import java.util.Optional;

public interface AtomSpi {

  void create(String key, byte[] value, String token, Duration ttl);

  Optional<RawAtom> read(String key);

  boolean set(String key, byte[] value, String token, Duration ttl);

  boolean touch(String key, Duration ttl);

  void delete(String key);

  /**
   * Delete up to {@code maxToSweep} expired records from the backend.
   *
   * <p>Implementations that rely on native backend TTL (Redis EXPIRE, DynamoDB TTL, Mongo TTL
   * indexes, etc.) should leave this method as the default no-op inherited from {@link
   * AbstractAtomSpi}.
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

  String atomKey(String name);
}
