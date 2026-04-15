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
package org.jwcarman.substrate.core.mailbox;

import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.core.sweep.Sweepable;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

/**
 * Backend SPI for Mailbox storage operations.
 *
 * <p>A mailbox supports single-delivery semantics: exactly one value may be delivered before the
 * mailbox expires. Each mailbox has a TTL that governs its lifetime. All methods may be called from
 * multiple threads concurrently and must be thread-safe.
 *
 * @see AbstractMailboxSpi
 */
public interface MailboxSpi extends Sweepable {

  /**
   * Creates a new mailbox with the given time-to-live.
   *
   * @param key the backend storage key
   * @param ttl the time-to-live for the mailbox
   */
  void create(String key, Duration ttl);

  /**
   * Deliver a value to a previously-created mailbox.
   *
   * @param key the backend storage key
   * @param value the serialized payload to deliver
   * @throws MailboxExpiredException if the mailbox no longer exists
   * @throws MailboxFullException if the mailbox already has a value
   */
  void deliver(String key, byte[] value);

  /**
   * Returns the delivered value, if one has been delivered and the mailbox has not expired.
   *
   * @param key the backend storage key
   * @return the delivered payload, or empty if not yet delivered or expired
   */
  Optional<byte[]> get(String key);

  /**
   * Returns whether a live mailbox exists at the given key.
   *
   * <p>A mailbox is "live" if it was created and has not expired, regardless of whether a value has
   * been delivered.
   *
   * @param key the backend storage key
   * @return {@code true} if a live mailbox exists at this key
   */
  boolean exists(String key);

  /**
   * Removes the mailbox. This operation is idempotent — deleting a non-existent mailbox is a no-op.
   *
   * @param key the backend storage key
   */
  void delete(String key);

  /**
   * Builds a backend storage key from a logical mailbox name.
   *
   * @param name the logical mailbox name
   * @return the fully-qualified backend key
   */
  String mailboxKey(String name);
}
