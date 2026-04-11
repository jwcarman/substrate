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
 * Fluent configuration interface for registering lifecycle handlers on a {@link Subscriber}. An
 * instance of this interface is passed as a customizer lambda to a primitive's {@code subscribe}
 * method:
 *
 * <pre>{@code
 * atom.subscribe(snapshot, cfg -> cfg
 *     .onNext(snap -> process(snap))
 *     .onExpired(() -> reconnect())
 *     .onDeleted(() -> cleanup())
 *     .onError(err -> log.error("subscription error", err)));
 * }</pre>
 *
 * <p>Alternatively, when a direct {@link Subscriber} lambda is sufficient:
 *
 * <pre>{@code
 * atom.subscribe(snapshot, snap -> process(snap));
 * }</pre>
 *
 * <p>This interface deliberately exposes no {@code build()} method and no static factories —
 * materializing a {@link Subscriber} from the configured handlers is an implementation detail.
 *
 * @param <T> the type of values delivered to the subscriber
 * @see Subscriber
 */
public interface SubscriberConfig<T> {

  /**
   * Registers the handler invoked for each delivered value.
   *
   * @param handler the value handler
   * @return this config, for chaining
   */
  SubscriberConfig<T> onNext(Consumer<T> handler);

  /**
   * Registers the handler invoked when the primitive completes naturally.
   *
   * @param handler the completion handler
   * @return this config, for chaining
   */
  SubscriberConfig<T> onCompleted(Runnable handler);

  /**
   * Registers the handler invoked when the primitive's TTL elapses.
   *
   * @param handler the expiration handler
   * @return this config, for chaining
   */
  SubscriberConfig<T> onExpired(Runnable handler);

  /**
   * Registers the handler invoked when the primitive is explicitly deleted.
   *
   * @param handler the deletion handler
   * @return this config, for chaining
   */
  SubscriberConfig<T> onDeleted(Runnable handler);

  /**
   * Registers the handler invoked when the local subscription is cancelled.
   *
   * @param handler the cancellation handler
   * @return this config, for chaining
   */
  SubscriberConfig<T> onCancelled(Runnable handler);

  /**
   * Registers the handler invoked on an unexpected feeder exception.
   *
   * @param handler the error handler
   * @return this config, for chaining
   */
  SubscriberConfig<T> onError(Consumer<Throwable> handler);
}
