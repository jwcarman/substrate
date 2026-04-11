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

/**
 * A distributed single-delivery mailbox with TTL-based expiration.
 *
 * <p>A {@code Mailbox} receives exactly one value in its lifetime. Once delivered, the value is
 * observable by any number of subscribers until the mailbox's TTL elapses or {@link #delete()} is
 * called. After the delivered value has been consumed by a subscriber, the subscription
 * auto-completes. In this sense a mailbox functions as a distributed {@link
 * java.util.concurrent.CompletableFuture} — one side creates the mailbox and waits, the other side
 * delivers the single response.
 *
 * <p>All implementations must be thread-safe. Multiple threads (or distributed nodes) may
 * concurrently attempt to deliver or subscribe without external synchronization.
 *
 * <h2>Usage examples</h2>
 *
 * <p><strong>Blocking pattern:</strong>
 *
 * <pre>{@code
 * // Producer side — elicitation request-response pattern
 * Mailbox<Response> mailbox =
 *     mailboxFactory.create("elicit:" + requestId, Response.class, Duration.ofMinutes(5));
 *
 * // Consumer side — wait for the single delivery (blocking)
 * BlockingSubscription<Response> sub = mailbox.subscribe();
 * switch (sub.next(Duration.ofMinutes(5))) {
 *     case NextResult.Value<Response>(var response) -> handleResponse(response);
 *     case NextResult.Timeout<Response> t -> handleTimeout();
 *     case NextResult.Expired<Response> e -> handleExpired();
 *     case NextResult.Deleted<Response> d -> handleCancelled();
 *     default -> { }
 * }
 * }</pre>
 *
 * <p><strong>Callback pattern:</strong>
 *
 * <pre>{@code
 * Mailbox<String> mailbox = factory.connect("request-123", String.class);
 * mailbox.subscribe(value -> System.out.println("Got: " + value));
 * }</pre>
 *
 * @param <T> the type of value this mailbox delivers
 * @see MailboxFactory
 */
public interface Mailbox<T> {

  /**
   * Deliver a value to this mailbox. A mailbox can receive exactly one delivery in its lifetime —
   * once delivered, the value is observable via {@link #subscribe} until the mailbox's TTL elapses
   * or {@link #delete} is called.
   *
   * @param value the value to deliver
   * @throws MailboxExpiredException if the mailbox has expired or been deleted
   * @throws MailboxFullException if a delivery has already occurred
   */
  void deliver(T value);

  /**
   * Delete this mailbox, discarding any undelivered or unconsumed value. Subscribers that are
   * currently waiting will be notified of the deletion. After deletion, subsequent calls to {@link
   * #deliver(Object)} will throw {@link MailboxExpiredException}.
   */
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

  /**
   * Returns the unique key that identifies this mailbox within the backing store.
   *
   * @return the mailbox key, never {@code null}
   */
  String key();
}
