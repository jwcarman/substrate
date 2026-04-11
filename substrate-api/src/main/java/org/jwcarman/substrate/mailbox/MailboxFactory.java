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

import java.time.Duration;
import org.jwcarman.codec.spi.TypeRef;

/**
 * Factory for constructing and connecting to {@link Mailbox} instances.
 *
 * <p>Two families of operations are provided:
 *
 * <ul>
 *   <li><strong>{@code create}</strong> — eagerly provisions a new mailbox in the backing store.
 *       This performs backend I/O and establishes the mailbox's TTL.
 *   <li><strong>{@code connect}</strong> — lazily obtains a handle to an existing mailbox. No
 *       backend I/O occurs until the first operation (e.g., {@link Mailbox#deliver} or {@link
 *       Mailbox#subscribe}) is invoked on the returned handle.
 * </ul>
 *
 * @see Mailbox
 */
public interface MailboxFactory {

  /**
   * Create a new mailbox with the given name and value type.
   *
   * @param <T> the mailbox value type
   * @param name the logical name for the mailbox
   * @param type the value type class
   * @param ttl how long the mailbox lives before expiring automatically
   * @return a newly provisioned mailbox
   */
  <T> Mailbox<T> create(String name, Class<T> type, Duration ttl);

  /**
   * Create a new mailbox with the given name and generic value type.
   *
   * @param <T> the mailbox value type
   * @param name the logical name for the mailbox
   * @param typeRef a type reference capturing the generic value type
   * @param ttl how long the mailbox lives before expiring automatically
   * @return a newly provisioned mailbox
   */
  <T> Mailbox<T> create(String name, TypeRef<T> typeRef, Duration ttl);

  /**
   * Connect to an existing mailbox. No backend I/O is performed until the first operation on the
   * returned handle.
   *
   * @param <T> the mailbox value type
   * @param name the logical name of the mailbox to connect to
   * @param type the value type class
   * @return a lazy handle to the mailbox
   */
  <T> Mailbox<T> connect(String name, Class<T> type);

  /**
   * Connect to an existing mailbox using a generic value type. No backend I/O is performed until
   * the first operation on the returned handle.
   *
   * @param <T> the mailbox value type
   * @param name the logical name of the mailbox to connect to
   * @param typeRef a type reference capturing the generic value type
   * @return a lazy handle to the mailbox
   */
  <T> Mailbox<T> connect(String name, TypeRef<T> typeRef);
}
