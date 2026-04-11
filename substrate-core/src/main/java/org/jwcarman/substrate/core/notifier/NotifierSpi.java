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
package org.jwcarman.substrate.core.notifier;

/**
 * Backend SPI for fire-and-forget notification broadcasting.
 *
 * <p>Notifications are delivered on a best-effort basis — they may be lost if no subscriber is
 * listening at the time of broadcast. Delivery is fan-out: every subscriber across all nodes
 * receives each notification. Key filtering is the responsibility of subscribers, not the backend.
 *
 * <p>Implementations must be thread-safe.
 */
public interface NotifierSpi {

  /**
   * Broadcasts a notification with the given key and payload to all current subscribers.
   * Best-effort — there is no delivery guarantee.
   *
   * @param key the notification key (typically a primitive's backend key)
   * @param payload a short signal payload
   */
  void notify(String key, String payload);

  /**
   * Registers a handler to receive all notifications. Returns a {@link NotifierSubscription} that
   * can be cancelled to stop receiving notifications.
   *
   * @param handler the handler to invoke for each notification
   * @return a subscription handle that can be cancelled
   */
  NotifierSubscription subscribe(NotificationHandler handler);
}
