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
package org.jwcarman.substrate.journal;

import java.time.Duration;
import org.jwcarman.codec.spi.TypeRef;

/**
 * Factory for constructing and connecting to {@link Journal} instances.
 *
 * <p>Two creation modes are supported:
 *
 * <ul>
 *   <li>{@code create} — eagerly provisions a new journal in the backend. Performs I/O immediately
 *       and throws {@link JournalAlreadyExistsException} if a journal with the same name already
 *       exists.
 *   <li>{@code connect} — returns a lazy handle to an existing journal. No backend I/O is performed
 *       until the first operation is invoked on the returned {@link Journal}.
 * </ul>
 *
 * @see Journal
 */
public interface JournalFactory {

  /**
   * Creates a new journal with the given name and inactivity TTL.
   *
   * <p>This method performs backend I/O immediately to provision the journal.
   *
   * @param <T> the entry payload type
   * @param name the unique name for the journal
   * @param type the payload class
   * @param inactivityTtl how long the journal lives without appends before auto-expiring
   * @return a new {@link Journal} instance
   * @throws JournalAlreadyExistsException if a journal with the given name already exists
   */
  <T> Journal<T> create(String name, Class<T> type, Duration inactivityTtl);

  /**
   * Creates a new journal with the given name and inactivity TTL, using a {@link TypeRef} for
   * generic payload types.
   *
   * <p>This method performs backend I/O immediately to provision the journal.
   *
   * @param <T> the entry payload type
   * @param name the unique name for the journal
   * @param typeRef a type reference capturing the full generic payload type
   * @param inactivityTtl how long the journal lives without appends before auto-expiring
   * @return a new {@link Journal} instance
   * @throws JournalAlreadyExistsException if a journal with the given name already exists
   */
  <T> Journal<T> create(String name, TypeRef<T> typeRef, Duration inactivityTtl);

  /**
   * Connects to an existing journal by name. No backend I/O is performed until the first operation
   * is invoked on the returned {@link Journal}.
   *
   * @param <T> the entry payload type
   * @param name the name of the journal to connect to
   * @param type the payload class
   * @return a lazy {@link Journal} handle
   */
  <T> Journal<T> connect(String name, Class<T> type);

  /**
   * Connects to an existing journal by name, using a {@link TypeRef} for generic payload types. No
   * backend I/O is performed until the first operation is invoked on the returned {@link Journal}.
   *
   * @param <T> the entry payload type
   * @param name the name of the journal to connect to
   * @param typeRef a type reference capturing the full generic payload type
   * @return a lazy {@link Journal} handle
   */
  <T> Journal<T> connect(String name, TypeRef<T> typeRef);
}
