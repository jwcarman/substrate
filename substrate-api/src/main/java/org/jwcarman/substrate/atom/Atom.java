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

/**
 * A distributed {@link java.util.concurrent.atomic.AtomicReference} with TTL-based leases and
 * change notification.
 *
 * <p>An Atom is a keyed, shared reference whose value can be read, written, and watched by multiple
 * processes. Every atom carries a time-to-live (TTL) lease; when the lease expires the atom is
 * considered dead and further mutations throw {@link AtomExpiredException}. Callers can extend the
 * lease with {@link #touch(Duration)}.
 *
 * <p>Subscriptions use <em>coalescing</em> semantics: subscribers always see the <em>current</em>
 * state of the atom, not a replay of every intermediate value. If the atom changes multiple times
 * between polls, the subscriber receives only the latest {@link Snapshot}. This is safe for use
 * from multiple threads.
 *
 * <h2>Usage examples</h2>
 *
 * <p><strong>Blocking pattern:</strong>
 *
 * <pre>{@code
 * Atom<Session> sessionAtom =
 *     atomFactory.create("session:abc", Session.class, session, Duration.ofHours(1));
 *
 * // Read current state
 * Snapshot<Session> current = sessionAtom.get();
 *
 * // Watch for changes (blocking style)
 * BlockingSubscription<Snapshot<Session>> sub = sessionAtom.subscribe(current);
 * while (sub.isActive()) {
 *     switch (sub.next(Duration.ofSeconds(30))) {
 *         case NextResult.Value<Snapshot<Session>>(var snap) -> process(snap);
 *         case NextResult.Timeout<Snapshot<Session>> t -> { }
 *         case NextResult.Expired<Snapshot<Session>> e -> handleExpired();
 *         case NextResult.Deleted<Snapshot<Session>> d -> handleDeleted();
 *         default -> { }
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Callback pattern:</strong>
 *
 * <pre>{@code
 * CallbackSubscription sub = atom.subscribe(snap ->
 *     System.out.println("New value: " + snap.value()));
 * // later...
 * sub.cancel();
 * }</pre>
 *
 * @param <T> the type of value held by this atom
 * @see AtomFactory
 * @see Snapshot
 */
public interface Atom<T> {

  /**
   * Sets the value of this atom and resets its TTL.
   *
   * @param data the new value to store
   * @param ttl the new time-to-live for this atom
   * @throws AtomExpiredException if the atom's lease has already elapsed or it has been deleted
   * @throws IllegalArgumentException if {@code ttl} exceeds the maximum allowed duration
   */
  void set(T data, Duration ttl);

  /**
   * Extends the TTL of this atom without changing its value.
   *
   * @param ttl the new time-to-live to apply
   * @return {@code true} if the lease was successfully extended, {@code false} if the atom is
   *     already dead (expired or deleted)
   */
  boolean touch(Duration ttl);

  /**
   * Returns the current value and staleness token of this atom.
   *
   * <p>This is a synchronous, non-blocking read.
   *
   * @return a {@link Snapshot} containing the current value and its associated token
   * @throws AtomExpiredException if the atom's lease has already elapsed or it has been deleted
   */
  Snapshot<T> get();

  /**
   * Explicitly deletes this atom, immediately expiring its lease.
   *
   * <p>After deletion, subsequent calls to {@link #set}, {@link #get}, and {@link #touch} will
   * behave as though the atom's TTL has elapsed.
   */
  void delete();

  /**
   * Subscribes from the current state using a blocking pull model. The first {@code next()} call
   * returns the current snapshot (if the atom exists); subsequent {@code next()} calls block until
   * the next {@link #set} occurs.
   *
   * @return a {@link BlockingSubscription} that yields {@link Snapshot} instances
   */
  BlockingSubscription<Snapshot<T>> subscribe();

  /**
   * Subscribes from a known baseline using a blocking pull model. If the atom's current token
   * differs from {@code lastSeen.token()}, the first {@code next()} call returns the current
   * snapshot immediately; otherwise the first {@code next()} blocks until the next change.
   *
   * @param lastSeen the baseline to compare against, or {@code null} for equivalent behavior to
   *     {@link #subscribe()}
   * @return a {@link BlockingSubscription} that yields {@link Snapshot} instances
   */
  BlockingSubscription<Snapshot<T>> subscribe(Snapshot<T> lastSeen);

  /**
   * Subscribes from the current state using a callback push model with only an {@code onNext}
   * handler.
   *
   * @param onNext the callback invoked each time the atom's value changes
   * @return a {@link CallbackSubscription} that can be used to cancel the subscription
   */
  CallbackSubscription subscribe(Consumer<Snapshot<T>> onNext);

  /**
   * Subscribes from the current state using a callback push model with an {@code onNext} handler
   * and additional lifecycle handlers configured via the {@code customizer}.
   *
   * @param onNext the callback invoked each time the atom's value changes
   * @param customizer a consumer that configures additional lifecycle handlers on the {@link
   *     CallbackSubscriberBuilder}
   * @return a {@link CallbackSubscription} that can be used to cancel the subscription
   */
  CallbackSubscription subscribe(
      Consumer<Snapshot<T>> onNext, Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer);

  /**
   * Subscribes from a known baseline using a callback push model with only an {@code onNext}
   * handler.
   *
   * @param lastSeen the baseline to compare against, or {@code null} to subscribe from current
   *     state
   * @param onNext the callback invoked each time the atom's value changes
   * @return a {@link CallbackSubscription} that can be used to cancel the subscription
   */
  CallbackSubscription subscribe(Snapshot<T> lastSeen, Consumer<Snapshot<T>> onNext);

  /**
   * Subscribes from a known baseline using a callback push model with an {@code onNext} handler and
   * additional lifecycle handlers configured via the {@code customizer}.
   *
   * @param lastSeen the baseline to compare against, or {@code null} to subscribe from current
   *     state
   * @param onNext the callback invoked each time the atom's value changes
   * @param customizer a consumer that configures additional lifecycle handlers on the {@link
   *     CallbackSubscriberBuilder}
   * @return a {@link CallbackSubscription} that can be used to cancel the subscription
   */
  CallbackSubscription subscribe(
      Snapshot<T> lastSeen,
      Consumer<Snapshot<T>> onNext,
      Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer);

  /**
   * Returns the backend key for this atom.
   *
   * @return the unique key identifying this atom in the backend store
   */
  String key();
}
