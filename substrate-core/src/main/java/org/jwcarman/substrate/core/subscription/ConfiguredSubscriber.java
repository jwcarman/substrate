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

/**
 * Immutable {@link Subscriber} implementation backed by individually registered handler fields.
 * Unset (null) handlers are silently ignored.
 *
 * @param <T> the type of values delivered to this subscriber
 */
public record ConfiguredSubscriber<T>(
    Consumer<T> onNextHandler,
    Runnable onCompletedHandler,
    Runnable onExpiredHandler,
    Runnable onDeletedHandler,
    Runnable onCancelledHandler,
    Consumer<Throwable> onErrorHandler)
    implements Subscriber<T> {

  @Override
  public void onNext(T value) {
    if (onNextHandler != null) onNextHandler.accept(value);
  }

  @Override
  public void onCompleted() {
    if (onCompletedHandler != null) onCompletedHandler.run();
  }

  @Override
  public void onExpired() {
    if (onExpiredHandler != null) onExpiredHandler.run();
  }

  @Override
  public void onDeleted() {
    if (onDeletedHandler != null) onDeletedHandler.run();
  }

  @Override
  public void onCancelled() {
    if (onCancelledHandler != null) onCancelledHandler.run();
  }

  @Override
  public void onError(Throwable cause) {
    if (onErrorHandler != null) onErrorHandler.accept(cause);
  }
}
