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
package org.jwcarman.substrate.core.memory;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * A value paired with an absolute expiry instant. Used by in-memory SPI implementations to attach
 * TTL semantics to arbitrary value types without reinventing the wrapper for each primitive.
 *
 * <p>Expiry is evaluated against the current wall-clock time. {@link #isExpired} reads {@code
 * Instant.now()} internally, so callers don't thread a clock through. Per the in-memory SPI
 * contract, expired entries are cleaned up lazily on read and eagerly by the sweeper.
 *
 * @param value the wrapped value
 * @param expiresAt the instant at which the entry becomes expired
 * @param <V> the wrapped value type
 */
public record ExpiringEntry<V>(V value, Instant expiresAt) {

  public boolean isExpired() {
    return !Instant.now().isBefore(expiresAt);
  }

  /**
   * Sweep expired entries from the given map up to {@code maxToSweep}. Iterates the map's entries
   * once, removes those whose {@link #isExpired} returns true, and stops after {@code maxToSweep}
   * removals.
   *
   * @return the number of entries removed
   */
  public static <K, V> int sweepExpired(ConcurrentMap<K, ExpiringEntry<V>> store, int maxToSweep) {
    int removed = 0;
    Iterator<Map.Entry<K, ExpiringEntry<V>>> iterator = store.entrySet().iterator();
    while (iterator.hasNext() && removed < maxToSweep) {
      if (iterator.next().getValue().isExpired()) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }
}
