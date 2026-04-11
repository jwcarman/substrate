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

/**
 * Skeletal implementation of {@link AtomSpi} providing key-prefix management and a no-op {@link
 * org.jwcarman.substrate.core.sweep.Sweepable#sweep sweep} default. Backend implementations extend
 * this and provide the storage-specific {@link AtomSpi#create create}, {@link AtomSpi#read read},
 * {@link AtomSpi#set set}, {@link AtomSpi#touch touch}, and {@link AtomSpi#delete delete}
 * operations.
 */
public abstract class AbstractAtomSpi implements AtomSpi {

  private final String prefix;

  protected AbstractAtomSpi(String prefix) {
    this.prefix = prefix;
  }

  protected String prefix() {
    return prefix;
  }

  @Override
  public int sweep(int maxToSweep) {
    return 0;
  }

  @Override
  public String atomKey(String name) {
    return prefix + name;
  }
}
