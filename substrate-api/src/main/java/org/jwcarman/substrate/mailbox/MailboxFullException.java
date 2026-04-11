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

/**
 * Thrown when {@link Mailbox#deliver(Object)} is called on a mailbox that has already received its
 * single delivery. A mailbox accepts exactly one value; any subsequent delivery attempt results in
 * this exception.
 *
 * @see Mailbox#deliver(Object)
 */
public class MailboxFullException extends RuntimeException {

  /**
   * Construct a new instance for the given mailbox key.
   *
   * @param key the key of the mailbox that already contains a delivered value
   */
  public MailboxFullException(String key) {
    super("Mailbox already has a delivered value: " + key);
  }
}
