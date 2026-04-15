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
package org.jwcarman.substrate.core.subscription;

import java.time.Duration;
import org.jwcarman.substrate.NextResult;

/**
 * Rendezvous buffer between a primitive's feeder thread and a subscription's consumer. The feeder
 * delivers values and terminal signals; the consumer polls for results. Delivery-guarantee
 * semantics (at-most-once, deliver-on-change, deliver-every-entry) are concerns of the primitive's
 * feeder, not the handoff — the handoff is a dumb buffer that parks the reader until a value or
 * terminal is available.
 *
 * <p>Terminals are sticky: once set, subsequent {@link #poll(Duration)} calls return the terminal
 * without blocking. Pending values always drain before the terminal is observed.
 *
 * @param <T> the type of values transferred through the handoff
 */
public interface Handoff<T> {

  /**
   * Delivers a value from the feeder to the consumer. Implementation-specific semantics govern
   * whether delivery blocks (backpressure) or overwrites a pending value (latest-wins).
   *
   * @param value the value to deliver
   */
  void deliver(T value);

  /**
   * Blocks up to the given timeout for the next result. Returns {@link NextResult.Timeout Timeout}
   * if nothing arrives within the deadline. Never blocks once a terminal has been set.
   *
   * @param timeout the maximum time to wait
   * @return the next result
   */
  NextResult<T> poll(Duration timeout);

  /**
   * Signals an unrecoverable backend error to the consumer.
   *
   * @param cause the error that occurred
   */
  void error(Throwable cause);

  /** Signals that the underlying primitive has been completed. */
  void markCompleted();

  /** Signals that the underlying primitive has expired. */
  void markExpired();

  /** Signals that the underlying primitive has been deleted. */
  void markDeleted();

  /**
   * Wakes any thread parked in {@link #poll(Duration) poll} by setting a {@link
   * NextResult.Cancelled Cancelled} terminal. Used by subscription cancellation and shutdown to
   * unblock a local consumer when the primitive itself is still alive.
   */
  void markCancelled();
}
