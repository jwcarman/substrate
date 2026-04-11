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
package org.jwcarman.substrate;

import java.time.Duration;

/**
 * A pull-based {@link Subscription} where the caller explicitly polls for values by calling {@link
 * #next(Duration)}. Each call blocks until a value is available, the timeout elapses, or a terminal
 * condition is reached.
 *
 * <p>After {@link #next(Duration)} returns a {@linkplain NextResult#isTerminal() terminal} result
 * ({@link NextResult.Completed}, {@link NextResult.Expired}, {@link NextResult.Deleted}, or {@link
 * NextResult.Errored}), {@link #isActive()} will return {@code false} and all subsequent calls to
 * {@code next()} will return the same terminal result.
 *
 * <p>If the calling thread is interrupted while blocked in {@code next()}, the subscription becomes
 * inactive and the thread's interrupt flag is restored.
 *
 * <p>{@code BlockingSubscription} extends {@link AutoCloseable} so it can be used with
 * try-with-resources. The {@link #close()} default implementation simply delegates to {@link
 * #cancel()}.
 *
 * @param <T> the type of values delivered by this subscription
 * @see Subscriber
 * @see NextResult
 */
public interface BlockingSubscription<T> extends Subscription, AutoCloseable {

  /**
   * Blocks until the next result is available or the given timeout elapses.
   *
   * <p>Possible outcomes:
   *
   * <ul>
   *   <li>{@link NextResult.Value} — a new value was delivered.
   *   <li>{@link NextResult.Timeout} — the timeout elapsed with no value available; the
   *       subscription is still active.
   *   <li>{@link NextResult.Completed} — the underlying primitive completed naturally.
   *   <li>{@link NextResult.Expired} — the underlying primitive's TTL elapsed.
   *   <li>{@link NextResult.Deleted} — the underlying primitive was explicitly deleted.
   *   <li>{@link NextResult.Errored} — an unexpected error occurred.
   * </ul>
   *
   * @param timeout the maximum time to wait for a result
   * @return a {@link NextResult} describing the outcome
   */
  NextResult<T> next(Duration timeout);

  /**
   * {@inheritDoc}
   *
   * <p>Equivalent to calling {@link #cancel()}. Provided so that {@code BlockingSubscription}
   * instances can be used with try-with-resources blocks. Does not throw.
   */
  @Override
  default void close() {
    cancel();
  }
}
