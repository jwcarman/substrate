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
package org.jwcarman.substrate.mailbox;

import java.util.function.Consumer;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;

public interface Mailbox<T> {

  /**
   * Deliver a value to this mailbox. A mailbox can receive exactly one delivery in its lifetime —
   * once delivered, the value is observable via {@link #subscribe} until the mailbox's TTL elapses
   * or {@link #delete} is called.
   *
   * @throws MailboxExpiredException if the mailbox has expired or been deleted
   * @throws MailboxFullException if a delivery has already occurred
   */
  void deliver(T value);

  void delete();

  /**
   * Subscribe to the mailbox's single delivery. If the mailbox already has a delivered value when
   * this method is called, the first {@code next()} call returns it immediately; otherwise the
   * first {@code next()} blocks waiting for delivery. After the single value has been consumed,
   * subsequent {@code next()} calls return {@code NextResult.Completed} (auto-transition from the
   * underlying {@code SingleShotHandoff}).
   */
  BlockingSubscription<T> subscribe();

  /**
   * Callback subscribe with only an onNext handler. The handler is invoked exactly once when the
   * single delivery arrives. After the handler returns, the subscription naturally terminates via
   * {@code Completed} and the feeder exits.
   */
  CallbackSubscription subscribe(Consumer<T> onNext);

  /**
   * Callback subscribe with onNext and additional lifecycle handlers. {@code onComplete} fires
   * after the delivered value is consumed by the handler; {@code onExpiration} fires if the
   * mailbox's TTL elapses before delivery; {@code onDelete} fires if the mailbox is explicitly
   * deleted before delivery.
   */
  CallbackSubscription subscribe(
      Consumer<T> onNext, Consumer<CallbackSubscriberBuilder<T>> customizer);

  String key();
}
