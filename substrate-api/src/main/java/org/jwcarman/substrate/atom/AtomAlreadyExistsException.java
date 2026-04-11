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

/**
 * Thrown by {@link AtomFactory#create AtomFactory.create} when an atom with the given name already
 * exists in the backend.
 *
 * @see AtomFactory#create(String, Class, Object, java.time.Duration)
 * @see AtomFactory#create(String, org.jwcarman.codec.spi.TypeRef, Object, java.time.Duration)
 */
public class AtomAlreadyExistsException extends RuntimeException {

  /**
   * Constructs a new {@code AtomAlreadyExistsException} for the given atom key.
   *
   * @param key the backend key of the atom that already exists
   */
  public AtomAlreadyExistsException(String key) {
    super("Atom already exists: " + key);
  }
}
