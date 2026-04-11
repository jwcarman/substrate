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
package org.jwcarman.substrate.atom;

import java.time.Duration;
import org.jwcarman.codec.spi.TypeRef;

/**
 * Factory for constructing and connecting to {@link Atom} instances.
 *
 * <p>Two creation modes are provided:
 *
 * <ul>
 *   <li><strong>{@link #create} (eager)</strong> — performs backend I/O immediately to write the
 *       initial value and establish the lease. Throws {@link AtomAlreadyExistsException} if an atom
 *       with the given name already exists.
 *   <li><strong>{@link #connect} (lazy)</strong> — returns an {@link Atom} handle without
 *       performing any backend I/O. A dead or non-existent atom is discovered only when the first
 *       operation ({@link Atom#get}, {@link Atom#set}, {@link Atom#touch}, or {@link
 *       Atom#subscribe}) is invoked.
 * </ul>
 *
 * @see Atom
 */
public interface AtomFactory {

  /**
   * Creates a new atom with the given name, initial value, and TTL.
   *
   * <p>This is an eager operation that immediately writes to the backend.
   *
   * @param <T> the value type
   * @param name the unique name for the atom
   * @param type the value's class
   * @param initialValue the initial value to store
   * @param ttl the initial time-to-live for the atom's lease
   * @return a new {@link Atom} handle
   * @throws AtomAlreadyExistsException if an atom with {@code name} already exists
   */
  <T> Atom<T> create(String name, Class<T> type, T initialValue, Duration ttl);

  /**
   * Creates a new atom with the given name, initial value, and TTL, using a {@link TypeRef} for
   * generic or complex value types.
   *
   * <p>This is an eager operation that immediately writes to the backend.
   *
   * @param <T> the value type
   * @param name the unique name for the atom
   * @param typeRef a type reference describing the value's generic type
   * @param initialValue the initial value to store
   * @param ttl the initial time-to-live for the atom's lease
   * @return a new {@link Atom} handle
   * @throws AtomAlreadyExistsException if an atom with {@code name} already exists
   */
  <T> Atom<T> create(String name, TypeRef<T> typeRef, T initialValue, Duration ttl);

  /**
   * Connects to an existing atom by name.
   *
   * <p>This is a lazy operation: no backend I/O is performed until the first operation on the
   * returned {@link Atom}. If the atom does not exist or has expired, the failure is surfaced at
   * that time.
   *
   * @param <T> the value type
   * @param name the name of the atom to connect to
   * @param type the value's class
   * @return an {@link Atom} handle for the named atom
   */
  <T> Atom<T> connect(String name, Class<T> type);

  /**
   * Connects to an existing atom by name, using a {@link TypeRef} for generic or complex value
   * types.
   *
   * <p>This is a lazy operation: no backend I/O is performed until the first operation on the
   * returned {@link Atom}. If the atom does not exist or has expired, the failure is surfaced at
   * that time.
   *
   * @param <T> the value type
   * @param name the name of the atom to connect to
   * @param typeRef a type reference describing the value's generic type
   * @return an {@link Atom} handle for the named atom
   */
  <T> Atom<T> connect(String name, TypeRef<T> typeRef);
}
