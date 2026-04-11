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
import org.jwcarman.substrate.CallbackSubscriberBuilder;

public class DefaultCallbackSubscriberBuilder<T> implements CallbackSubscriberBuilder<T> {

  private Consumer<Throwable> onError;
  private Runnable onExpiration;
  private Runnable onDelete;
  private Runnable onComplete;
  private Runnable onCancel;

  @Override
  public CallbackSubscriberBuilder<T> onError(Consumer<Throwable> consumer) {
    this.onError = consumer;
    return this;
  }

  @Override
  public CallbackSubscriberBuilder<T> onExpiration(Runnable runnable) {
    this.onExpiration = runnable;
    return this;
  }

  @Override
  public CallbackSubscriberBuilder<T> onDelete(Runnable runnable) {
    this.onDelete = runnable;
    return this;
  }

  @Override
  public CallbackSubscriberBuilder<T> onComplete(Runnable runnable) {
    this.onComplete = runnable;
    return this;
  }

  @Override
  public CallbackSubscriberBuilder<T> onCancel(Runnable runnable) {
    this.onCancel = runnable;
    return this;
  }

  public LifecycleCallbacks<T> callbacks() {
    return new LifecycleCallbacks<>(onError, onExpiration, onDelete, onComplete, onCancel);
  }

  public Consumer<Throwable> errorHandler() {
    return onError;
  }

  public Runnable expirationHandler() {
    return onExpiration;
  }

  public Runnable deleteHandler() {
    return onDelete;
  }

  public Runnable completeHandler() {
    return onComplete;
  }

  public Runnable cancelHandler() {
    return onCancel;
  }
}
