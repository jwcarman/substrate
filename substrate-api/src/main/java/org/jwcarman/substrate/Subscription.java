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
 * Base interface for all substrate subscriptions. Every subscription has an active/inactive
 * lifecycle: once {@link #cancel()} is called, {@link #isActive()} returns {@code false} and no
 * further values will be delivered.
 *
 * <p>Subscriptions also become inactive when a terminal condition is reached (completion,
 * expiration, deletion, or error). Calling {@link #cancel()} on an already-inactive subscription is
 * a no-op.
 *
 * <p>The two concrete subscription styles are:
 *
 * <ul>
 *   <li>{@link BlockingSubscription} — pull-based, caller polls for values via {@link
 *       BlockingSubscription#next(java.time.Duration) next(Duration)}.
 *   <li>{@link CallbackSubscription} — push-based, values are delivered to a registered handler on
 *       a background thread.
 * </ul>
 *
 * @see BlockingSubscription
 * @see CallbackSubscription
 */
public interface Subscription {

  /**
   * Returns {@code true} if this subscription is still active and may deliver additional values.
   * Returns {@code false} after {@link #cancel()} has been called or after a terminal event
   * (completion, expiration, deletion, or error) has occurred.
   *
   * @return {@code true} if the subscription is active, {@code false} otherwise
   */
  boolean isActive();

  /**
   * Cancels this subscription. After this method returns, {@link #isActive()} will return {@code
   * false} and no further values will be delivered. Calling {@code cancel()} on an already-inactive
   * subscription has no effect.
   */
  void cancel();
}
