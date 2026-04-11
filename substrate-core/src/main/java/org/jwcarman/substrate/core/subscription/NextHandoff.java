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
import java.util.List;
import org.jwcarman.substrate.NextResult;

/**
 * Contract for the producer-consumer handoff between a primitive's feeder thread and a
 * subscription's consumer. The feeder pushes values and terminal signals; the consumer pulls
 * results. Implementations define the buffering, backpressure, and coalescing strategy.
 *
 * @param <T> the type of values transferred through the handoff
 */
public interface NextHandoff<T> {

  /**
   * Delivers a single value from the feeder to the consumer.
   *
   * @param item the value to deliver
   */
  void push(T item);

  /**
   * Delivers multiple values from the feeder to the consumer. The exact semantics (ordered enqueue,
   * coalesce to latest, etc.) depend on the implementation strategy.
   *
   * @param items the values to deliver
   */
  void pushAll(List<T> items);

  /**
   * Blocks up to the given timeout for the next result. Returns {@link NextResult.Timeout Timeout}
   * if nothing arrives within the deadline.
   *
   * @param timeout the maximum time to wait
   * @return the next result, or {@link NextResult.Timeout Timeout} if the deadline elapses
   */
  NextResult<T> pull(Duration timeout);

  /**
   * Signals an unrecoverable backend error to the consumer.
   *
   * @param cause the error that occurred
   */
  void error(Throwable cause);

  /**
   * Signals that the underlying primitive has been completed. Terminal — no further values will be
   * delivered after this signal is consumed.
   */
  void markCompleted();

  /**
   * Signals that the underlying primitive has expired. Terminal — no further values will be
   * delivered after this signal is consumed.
   */
  void markExpired();

  /**
   * Signals that the underlying primitive has been deleted. Terminal — no further values will be
   * delivered after this signal is consumed.
   */
  void markDeleted();

  /**
   * Wakes any thread currently blocked in {@link #pull(Duration) pull} without signaling any
   * primitive-level terminal state. Used by subscription cancellation and shutdown to unblock a
   * local consumer when the primitive itself is still alive.
   *
   * <p>The woken {@code pull()} should return a {@link NextResult.Timeout Timeout} so the caller
   * can loop back, observe {@link org.jwcarman.substrate.Subscription#isActive isActive()} as
   * {@code false}, and exit cleanly.
   *
   * <p>Implementations must not overwrite a pending {@link NextResult.Value Value} — if the slot
   * already holds a value, the consumer's next pull will still observe that value on its way out.
   */
  void markCancelled();
}
