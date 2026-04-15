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
package org.jwcarman.substrate.core.atom;

import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.sweep.Sweepable;

/**
 * Backend SPI for Atom storage operations.
 *
 * <p>Implementations must provide atomic create-if-not-exists, read, conditional set (returning
 * success or failure for compare-and-swap semantics), touch (TTL renewal), and delete operations.
 * All methods may be called from multiple threads concurrently and must be thread-safe.
 *
 * <p>Backends that support native TTL typically inherit a no-op {@link
 * org.jwcarman.substrate.core.sweep.Sweepable#sweep sweep} from {@link AbstractAtomSpi}.
 *
 * @see AbstractAtomSpi
 * @see RawAtom
 */
public interface AtomSpi extends Sweepable {

  /**
   * Atomically creates an atom if one does not already exist at the given key.
   *
   * @param key the backend storage key
   * @param value the serialized payload
   * @param token the opaque staleness marker for change detection
   * @param ttl the time-to-live for the atom
   * @throws AtomAlreadyExistsException if an atom already exists at {@code key}
   */
  void create(String key, byte[] value, String token, Duration ttl);

  /**
   * Reads the current state of an atom.
   *
   * @param key the backend storage key
   * @return the raw atom, or empty if the atom has expired or been deleted
   */
  Optional<RawAtom> read(String key);

  /**
   * Writes a new value and token for an existing atom, resetting its TTL.
   *
   * @param key the backend storage key
   * @param value the new serialized payload
   * @param token the new opaque staleness marker
   * @param ttl the new time-to-live
   * @return {@code true} if the atom existed and was updated; {@code false} if the atom does not
   *     exist (expired or deleted)
   */
  boolean set(String key, byte[] value, String token, Duration ttl);

  /**
   * Extends the TTL of an existing atom without changing its value or token.
   *
   * @param key the backend storage key
   * @param ttl the new time-to-live
   * @return {@code true} if the atom existed and its TTL was renewed; {@code false} if the atom
   *     does not exist (expired or deleted)
   */
  boolean touch(String key, Duration ttl);

  /**
   * Returns whether a live atom exists at the given key.
   *
   * <p>An atom is "live" if it was created, has not expired, and has not been deleted.
   *
   * @param key the backend storage key
   * @return {@code true} if a live atom exists at this key
   */
  boolean exists(String key);

  /**
   * Removes the atom at the given key. This operation is idempotent — deleting a non-existent atom
   * is a no-op.
   *
   * @param key the backend storage key
   */
  void delete(String key);

  /**
   * Builds a backend storage key from a logical atom name.
   *
   * @param name the logical atom name
   * @return the fully-qualified backend key
   */
  String atomKey(String name);
}
