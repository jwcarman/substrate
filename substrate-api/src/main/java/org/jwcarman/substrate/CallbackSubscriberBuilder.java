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

import java.util.function.Consumer;

/**
 * Builder for configuring lifecycle handlers on a {@link CallbackSubscription}. An instance of this
 * builder is passed as a customizer lambda to the subscribe methods on substrate primitives.
 *
 * <p>The {@code onNext} handler is <em>not</em> part of this builder — it is a required positional
 * argument to the subscribe method itself. This builder configures the optional lifecycle handlers
 * that fire on terminal or exceptional events.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * CallbackSubscription sub = atom.subscribe(
 *     current,
 *     snap -> process(snap),        // onNext (required, positional)
 *     b -> b
 *         .onExpiration(() -> reconnect())
 *         .onDelete(() -> cleanup())
 *         .onError(err -> log.error("subscription error", err)));
 * }</pre>
 *
 * @param <T> the type of values delivered by the subscription
 * @see CallbackSubscription
 */
public interface CallbackSubscriberBuilder<T> {

  /**
   * Registers a handler that is invoked when an unexpected error occurs during value delivery. The
   * subscription becomes inactive after the handler returns.
   *
   * @param consumer the error handler
   * @return this builder, for chaining
   */
  CallbackSubscriberBuilder<T> onError(Consumer<Throwable> consumer);

  /**
   * Registers a handler that is invoked when the underlying primitive's TTL elapses without
   * renewal. The subscription becomes inactive after the handler returns.
   *
   * @param runnable the expiration handler
   * @return this builder, for chaining
   */
  CallbackSubscriberBuilder<T> onExpiration(Runnable runnable);

  /**
   * Registers a handler that is invoked when the underlying primitive is explicitly deleted. The
   * subscription becomes inactive after the handler returns.
   *
   * @param runnable the deletion handler
   * @return this builder, for chaining
   */
  CallbackSubscriberBuilder<T> onDelete(Runnable runnable);

  /**
   * Registers a handler that is invoked when the underlying primitive completes naturally (e.g., a
   * Journal after {@code complete()} and drain, or a Mailbox after its single delivery is
   * consumed). The subscription becomes inactive after the handler returns.
   *
   * @param runnable the completion handler
   * @return this builder, for chaining
   */
  CallbackSubscriberBuilder<T> onComplete(Runnable runnable);

  /**
   * Registers a handler that is invoked when this subscription is cancelled — either via an
   * explicit {@link Subscription#cancel() sub.cancel()} call or by the substrate shutdown
   * coordinator during Spring context close. The underlying primitive is <em>not</em> affected —
   * this is a local-only teardown signal. The subscription becomes inactive after the handler
   * returns.
   *
   * <p>User-initiated cancel is already observable to the caller (they made the call), so this
   * handler is most useful for coordinated shutdown — your app can react to being torn down by
   * substrate's shutdown path even though your code never called {@code cancel()} directly.
   *
   * @param runnable the cancel handler
   * @return this builder, for chaining
   */
  CallbackSubscriberBuilder<T> onCancel(Runnable runnable);
}
