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

import java.util.function.Consumer;
import org.jwcarman.substrate.Subscriber;
import org.jwcarman.substrate.SubscriberConfig;

/**
 * Mutable builder that implements {@link SubscriberConfig} and materializes a {@link
 * ConfiguredSubscriber} via {@link #build()}.
 *
 * @param <T> the type of values delivered to the subscriber
 */
public final class DefaultSubscriberBuilder<T> implements SubscriberConfig<T> {

  private Consumer<T> onNext;
  private Runnable onCompleted;
  private Runnable onExpired;
  private Runnable onDeleted;
  private Runnable onCancelled;
  private Consumer<Throwable> onError;

  @Override
  public DefaultSubscriberBuilder<T> onNext(Consumer<T> handler) {
    this.onNext = handler;
    return this;
  }

  @Override
  public DefaultSubscriberBuilder<T> onCompleted(Runnable handler) {
    this.onCompleted = handler;
    return this;
  }

  @Override
  public DefaultSubscriberBuilder<T> onExpired(Runnable handler) {
    this.onExpired = handler;
    return this;
  }

  @Override
  public DefaultSubscriberBuilder<T> onDeleted(Runnable handler) {
    this.onDeleted = handler;
    return this;
  }

  @Override
  public DefaultSubscriberBuilder<T> onCancelled(Runnable handler) {
    this.onCancelled = handler;
    return this;
  }

  @Override
  public DefaultSubscriberBuilder<T> onError(Consumer<Throwable> handler) {
    this.onError = handler;
    return this;
  }

  /**
   * Materializes a {@link ConfiguredSubscriber} from the registered handlers.
   *
   * @return the configured subscriber
   * @throws IllegalStateException if no {@code onNext} handler was registered
   */
  public ConfiguredSubscriber<T> build() {
    if (onNext == null) {
      throw new IllegalStateException("SubscriberConfig requires an onNext handler");
    }
    return new ConfiguredSubscriber<>(
        onNext, onCompleted, onExpired, onDeleted, onCancelled, onError);
  }

  /**
   * Bridge helper that turns a {@link SubscriberConfig} customizer into a finished {@link
   * Subscriber}.
   *
   * @param <T> the value type
   * @param customizer the configuration callback
   * @return the materialized subscriber
   */
  public static <T> Subscriber<T> from(Consumer<SubscriberConfig<T>> customizer) {
    var builder = new DefaultSubscriberBuilder<T>();
    customizer.accept(builder);
    return builder.build();
  }
}
