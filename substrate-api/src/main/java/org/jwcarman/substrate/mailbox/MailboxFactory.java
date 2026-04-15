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
 *       This performs backend I/O and establishes the mailbox's TTL. If a mailbox with the given
 *       name already exists, name collision is the sole trigger for failure — the TTL passed here
 *       is not compared against the pre-existing mailbox; policy reconciliation is the caller's
 *       responsibility.
 *   <li><strong>{@code connect}</strong> — returns a handle to an existing mailbox without
 *       performing backend I/O at factory call time. The first deliver or subscribe operation
 *       ({@link Mailbox#deliver} or {@link Mailbox#subscribe}) probes the backend once: if no
 *       mailbox exists at {@code name}, that first operation throws {@link
 *       MailboxNotFoundException}. {@link Mailbox#delete()} does not probe and remains a retry-safe
 *       no-op when the mailbox is already gone. Once the one-shot probe passes, subsequent
 *       operations behave exactly as they would on a {@code create}-sourced handle — terminal-state
 *       transitions surface as {@link MailboxExpiredException} in the normal way.
 * </ul>
 *
 * @see Mailbox
 */
public interface MailboxFactory {

  /**
   * Create a new mailbox with the given name and value type.
   *
   * <p>If a mailbox with {@code name} already exists, name collision is the sole trigger for
   * failure — the {@code ttl} passed here is not compared against any pre-existing mailbox's
   * configuration; policy reconciliation is the caller's responsibility.
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
   * <p>If a mailbox with {@code name} already exists, name collision is the sole trigger for
   * failure — the {@code ttl} passed here is not compared against any pre-existing mailbox's
   * configuration; policy reconciliation is the caller's responsibility.
   *
   * @param <T> the mailbox value type
   * @param name the logical name for the mailbox
   * @param typeRef a type reference capturing the generic value type
   * @param ttl how long the mailbox lives before expiring automatically
   * @return a newly provisioned mailbox
   */
  <T> Mailbox<T> create(String name, TypeRef<T> typeRef, Duration ttl);

  /**
   * Connect to an existing mailbox.
   *
   * <p>No backend I/O is performed at factory call time. The first deliver or subscribe operation
   * on the returned handle probes the backend once and throws {@link MailboxNotFoundException} if
   * no mailbox exists at {@code name}. {@link Mailbox#delete()} does not probe and remains a
   * retry-safe no-op. Once the one-shot probe passes, the handle behaves exactly as one returned
   * from {@code create} — subsequent terminal-state transitions surface as {@link
   * MailboxExpiredException}.
   *
   * @param <T> the mailbox value type
   * @param name the logical name of the mailbox to connect to
   * @param type the value type class
   * @return a handle to the mailbox
   * @throws MailboxNotFoundException at first operation, if no mailbox exists at {@code name}
   *     (surfaced on the first deliver/subscribe call, not at {@code connect} time)
   */
  <T> Mailbox<T> connect(String name, Class<T> type);

  /**
   * Connect to an existing mailbox using a generic value type.
   *
   * <p>No backend I/O is performed at factory call time. The first deliver or subscribe operation
   * on the returned handle probes the backend once and throws {@link MailboxNotFoundException} if
   * no mailbox exists at {@code name}. {@link Mailbox#delete()} does not probe and remains a
   * retry-safe no-op. Once the one-shot probe passes, the handle behaves exactly as one returned
   * from {@code create} — subsequent terminal-state transitions surface as {@link
   * MailboxExpiredException}.
   *
   * @param <T> the mailbox value type
   * @param name the logical name of the mailbox to connect to
   * @param typeRef a type reference capturing the generic value type
   * @return a handle to the mailbox
   * @throws MailboxNotFoundException at first operation, if no mailbox exists at {@code name}
   *     (surfaced on the first deliver/subscribe call, not at {@code connect} time)
   */
  <T> Mailbox<T> connect(String name, TypeRef<T> typeRef);
}
