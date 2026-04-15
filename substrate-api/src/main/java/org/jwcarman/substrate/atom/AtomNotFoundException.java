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
 * Thrown when the first operation on a handle obtained from {@link AtomFactory#connect(String,
 * Class) AtomFactory.connect} finds that no atom exists at the given name. Signals an identity-time
 * mismatch (wrong name), distinct from {@link AtomExpiredException} (resource existed but has
 * expired).
 *
 * @see AtomFactory
 */
public class AtomNotFoundException extends RuntimeException {

  /**
   * Creates an exception indicating no atom exists at the given backend key.
   *
   * @param key the backend key of the missing atom
   */
  public AtomNotFoundException(String key) {
    super("Atom not found: " + key);
  }
}
