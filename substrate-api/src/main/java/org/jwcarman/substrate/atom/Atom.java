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
package org.jwcarman.substrate.atom;

import java.time.Duration;
import java.util.function.Consumer;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;

public interface Atom<T> {

  void set(T data, Duration ttl);

  boolean touch(Duration ttl);

  Snapshot<T> get();

  void delete();

  /**
   * Subscribe from the current state. The first {@code next()} call returns the current snapshot
   * (if the atom exists); subsequent {@code next()} calls block for the next {@code set()}.
   */
  BlockingSubscription<Snapshot<T>> subscribe();

  /**
   * Subscribe from a known baseline. If the atom's current token differs from {@code
   * lastSeen.token()}, the first {@code next()} call returns the current snapshot; otherwise the
   * first {@code next()} blocks for the next change.
   *
   * @param lastSeen the baseline to compare against, or null for equivalent behavior to {@link
   *     #subscribe()}
   */
  BlockingSubscription<Snapshot<T>> subscribe(Snapshot<T> lastSeen);

  /** Callback subscribe with only an onNext handler, from current state. */
  CallbackSubscription subscribe(Consumer<Snapshot<T>> onNext);

  /** Callback subscribe with onNext and additional lifecycle handlers. */
  CallbackSubscription subscribe(
      Consumer<Snapshot<T>> onNext, Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer);

  /** Callback subscribe from a known baseline, with only onNext. */
  CallbackSubscription subscribe(Snapshot<T> lastSeen, Consumer<Snapshot<T>> onNext);

  /** Callback subscribe from a known baseline with additional handlers. */
  CallbackSubscription subscribe(
      Snapshot<T> lastSeen,
      Consumer<Snapshot<T>> onNext,
      Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer);

  String key();
}
