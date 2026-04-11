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

/**
 * Sealed outcome type returned by {@link BlockingSubscription#next(java.time.Duration)}. Each call
 * to {@code next()} returns exactly one of six variants:
 *
 * <ul>
 *   <li>{@link Value} — a new value from the underlying primitive (non-terminal).
 *   <li>{@link Timeout} — the timeout elapsed with no value available (non-terminal).
 *   <li>{@link Completed} — the primitive completed naturally (terminal).
 *   <li>{@link Expired} — the primitive's TTL elapsed without renewal (terminal).
 *   <li>{@link Deleted} — the primitive was explicitly deleted (terminal).
 *   <li>{@link Errored} — an unexpected backend error occurred (terminal).
 * </ul>
 *
 * <p>Callers should use pattern matching ({@code switch} expressions or {@code instanceof} checks)
 * to handle each variant. The {@link #isTerminal()} method provides a quick check: terminal results
 * indicate that no further values will arrive and the subscription is now inactive.
 *
 * @param <T> the type of values carried by {@link Value} results
 * @see BlockingSubscription
 */
public sealed interface NextResult<T>
    permits NextResult.Value,
        NextResult.Timeout,
        NextResult.Completed,
        NextResult.Expired,
        NextResult.Deleted,
        NextResult.Errored {

  /**
   * Returns {@code true} if this result represents the end of the subscription — no more values
   * will arrive. {@link Completed}, {@link Expired}, {@link Deleted}, and {@link Errored} are
   * terminal. {@link Value} and {@link Timeout} are not — more values may still arrive on
   * subsequent pulls.
   */
  default boolean isTerminal() {
    return true;
  }

  /**
   * A new value was delivered from the underlying primitive. This is a non-terminal result; the
   * subscription remains active and subsequent calls to {@link
   * BlockingSubscription#next(java.time.Duration)} may yield additional values.
   *
   * @param value the delivered value
   * @param <T> the value type
   */
  record Value<T>(T value) implements NextResult<T> {
    @Override
    public boolean isTerminal() {
      return false;
    }
  }

  /**
   * The specified timeout elapsed before a value became available. This is a non-terminal result;
   * the subscription remains active and the caller may retry by calling {@link
   * BlockingSubscription#next(java.time.Duration)} again.
   *
   * @param <T> the value type
   */
  record Timeout<T>() implements NextResult<T> {
    @Override
    public boolean isTerminal() {
      return false;
    }
  }

  /**
   * The underlying primitive completed naturally. For a Journal, this occurs after {@code
   * complete()} is called and all remaining entries have been drained. For a Mailbox, this occurs
   * after the single delivery has been consumed. Atoms never complete naturally. This is a terminal
   * result.
   *
   * @param <T> the value type
   */
  record Completed<T>() implements NextResult<T> {}

  /**
   * The underlying primitive's TTL elapsed without renewal, causing it to expire. This is a
   * terminal result.
   *
   * @param <T> the value type
   */
  record Expired<T>() implements NextResult<T> {}

  /**
   * The underlying primitive was explicitly deleted. This is a terminal result.
   *
   * @param <T> the value type
   */
  record Deleted<T>() implements NextResult<T> {}

  /**
   * An unexpected backend error occurred. The {@link #cause()} method returns the underlying
   * exception. This is a terminal result.
   *
   * @param cause the error that caused the subscription to terminate
   * @param <T> the value type
   */
  record Errored<T>(Throwable cause) implements NextResult<T> {}
}
