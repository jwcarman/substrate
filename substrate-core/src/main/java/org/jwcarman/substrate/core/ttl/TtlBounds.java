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
package org.jwcarman.substrate.core.ttl;

import java.time.Duration;

/**
 * Shared TTL bound checks for the core primitive implementations.
 *
 * <p>Two distinct rules live here because substrate's TTL parameters fall into two groups.
 *
 * <p><strong>Leases</strong> — an Atom's TTL, a Mailbox's TTL, and a Journal's inactivity TTL all
 * answer "how long does this resource stay alive". A non-positive lease has no coherent meaning and
 * backends disagree violently about what to do with one: Hazelcast's {@code IMap} reads {@code 0}
 * as <em>infinite</em>, the in-memory, PostgreSQL and MongoDB backends read it as <em>already
 * expired</em>, DynamoDB writes no expiry attribute at all, Redis rejects {@code EXPIRE key 0}
 * outright, and NATS ignores the value. {@link #requirePositiveAtMost} rejects it up front so every
 * backend agrees.
 *
 * <p><strong>Retention hints</strong> — a Journal entry TTL and a Journal retention TTL are
 * per-record hints rather than leases, and {@code Duration.ZERO} has an established meaning there:
 * the Cassandra, Redis, MongoDB, DynamoDB and Hazelcast journal SPIs all read zero as "store this
 * without a TTL". Those parameters are bounded from above only, via {@link #requireAtMost}.
 */
public final class TtlBounds {

  private TtlBounds() {}

  /**
   * Validates a lease TTL against both bounds.
   *
   * @param label the human-readable parameter name used in the exception message, e.g. {@code "Atom
   *     TTL"}
   * @param ttl the requested time-to-live
   * @param maxTtl the configured upper bound
   * @throws IllegalArgumentException if {@code ttl} is zero, negative, or greater than {@code
   *     maxTtl}
   */
  public static void requirePositiveAtMost(String label, Duration ttl, Duration maxTtl) {
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException(label + " " + ttl + " must be positive");
    }
    requireAtMost(label, ttl, maxTtl);
  }

  /**
   * Validates a TTL against its upper bound, leaving {@code Duration.ZERO} legal but rejecting
   * negative durations.
   *
   * <p>A negative TTL has no meaning on any backend and none implements one deliberately — it is
   * instead destructive. Redis reads a non-positive {@code EXPIRE} as "delete this key", so a
   * negative entry TTL would wipe out the whole stream including every previously appended entry;
   * Cassandra rejects {@code USING TTL -1} with a query error, and MongoDB sweeps the document
   * immediately.
   *
   * @param label the human-readable parameter name used in the exception message, e.g. {@code
   *     "Journal entry TTL"}
   * @param ttl the requested time-to-live
   * @param maxTtl the configured upper bound
   * @throws IllegalArgumentException if {@code ttl} is negative or greater than {@code maxTtl}
   */
  public static void requireAtMost(String label, Duration ttl, Duration maxTtl) {
    if (ttl.isNegative()) {
      throw new IllegalArgumentException(label + " " + ttl + " must not be negative");
    }
    if (ttl.compareTo(maxTtl) > 0) {
      throw new IllegalArgumentException(
          label + " " + ttl + " exceeds configured maximum " + maxTtl);
    }
  }
}
