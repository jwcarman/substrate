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

/**
 * Bundles the five optional lifecycle handlers that a callback-style subscription can register:
 * {@code onError}, {@code onExpiration}, {@code onDelete}, {@code onComplete}, and {@code
 * onCancel}. Any field may be {@code null} to indicate "no handler registered for this event."
 *
 * <p>Use {@link #empty()} for the no-handlers case instead of writing out five {@code null}s.
 *
 * @param <T> the value type of the enclosing subscription (carried for API symmetry with {@code
 *     CallbackSubscriberBuilder<T>}; the handlers themselves don't reference it)
 */
public record LifecycleCallbacks<T>(
    Consumer<Throwable> onError,
    Runnable onExpiration,
    Runnable onDelete,
    Runnable onComplete,
    Runnable onCancel) {

  /** Returns a {@code LifecycleCallbacks} with no handlers registered. */
  public static <T> LifecycleCallbacks<T> empty() {
    return new LifecycleCallbacks<>(null, null, null, null, null);
  }
}
