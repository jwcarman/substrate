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
 * Callback-style observer for values delivered by a substrate primitive subscription. Implement
 * {@link #onNext(Object)} to handle each delivered value; the remaining methods fire on terminal or
 * exceptional events and default to no-ops.
 *
 * <p>Because {@code Subscriber} is a {@link FunctionalInterface}, a plain lambda targeting {@code
 * onNext} is sufficient for the simplest use case:
 *
 * <pre>{@code
 * Subscriber<String> sub = value -> System.out.println("Got: " + value);
 * }</pre>
 *
 * <p>For richer lifecycle handling, use the {@link SubscriberConfig} customizer pattern via a
 * primitive's {@code subscribe} method:
 *
 * <pre>{@code
 * atom.subscribe(snapshot, cfg -> cfg
 *     .onNext(snap -> process(snap))
 *     .onExpired(() -> reconnect())
 *     .onError(err -> log.error("boom", err)));
 * }</pre>
 *
 * <h2>When each method fires</h2>
 *
 * <ul>
 *   <li>{@link #onNext(Object)} — each time a new value is available
 *   <li>{@link #onCompleted()} — the primitive completed naturally (journal drained, mailbox
 *       delivered)
 *   <li>{@link #onExpired()} — the primitive's TTL elapsed without renewal
 *   <li>{@link #onDeleted()} — the primitive was explicitly deleted
 *   <li>{@link #onCancelled()} — the local subscription was torn down (user {@code cancel()} or
 *       shutdown coordinator); the underlying primitive is unaffected
 *   <li>{@link #onError(Throwable)} — an unexpected exception occurred in the feeder
 * </ul>
 *
 * @param <T> the type of values delivered to this subscriber
 * @see SubscriberConfig
 */
@FunctionalInterface
public interface Subscriber<T> {

  /**
   * Called each time a new value is available from the subscription.
   *
   * @param value the delivered value
   */
  void onNext(T value);

  /** Called when the underlying primitive completes naturally. Does nothing by default. */
  default void onCompleted() {}

  /**
   * Called when the underlying primitive's TTL elapses without renewal. Does nothing by default.
   */
  default void onExpired() {}

  /** Called when the underlying primitive is explicitly deleted. Does nothing by default. */
  default void onDeleted() {}

  /**
   * Called when the local subscription is torn down via {@code cancel()} or the shutdown
   * coordinator. The underlying primitive is unaffected. Does nothing by default.
   */
  default void onCancelled() {}

  /**
   * Called when an unexpected exception occurs in the feeder. Does nothing by default.
   *
   * @param cause the exception
   */
  default void onError(Throwable cause) {}
}
