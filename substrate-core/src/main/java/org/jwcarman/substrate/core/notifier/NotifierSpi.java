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

import java.util.function.Consumer;

/**
 * Backend SPI for opaque byte[] notification broadcasting.
 *
 * <p>The SPI is a single broadcast channel. All type-awareness, routing, and fan-out logic is
 * handled by {@link DefaultNotifier}, which wraps this SPI. Backend implementations only need to
 * transport opaque byte arrays.
 *
 * <p>Implementations must be thread-safe.
 */
public interface NotifierSpi {

  /**
   * Broadcasts an opaque payload to all current subscribers. Best-effort — there is no delivery
   * guarantee.
   *
   * @param payload the encoded notification bytes
   */
  void notify(byte[] payload);

  /**
   * Registers a handler to receive all broadcast payloads. Returns a {@link NotifierSubscription}
   * that can be cancelled to stop receiving notifications.
   *
   * @param handler the handler to invoke for each payload
   * @return a subscription handle that can be cancelled
   */
  NotifierSubscription subscribe(Consumer<byte[]> handler);
}
