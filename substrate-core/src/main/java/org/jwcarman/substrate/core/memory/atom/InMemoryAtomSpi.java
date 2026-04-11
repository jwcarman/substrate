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
package org.jwcarman.substrate.core.memory.atom;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.jwcarman.substrate.core.memory.ExpiringEntry;

public class InMemoryAtomSpi extends AbstractAtomSpi {

  private final ConcurrentMap<String, ExpiringEntry<RawAtom>> store = new ConcurrentHashMap<>();

  public InMemoryAtomSpi() {
    super("substrate:atom:");
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    ExpiringEntry<RawAtom> entry =
        new ExpiringEntry<>(new RawAtom(value, token), Instant.now().plus(ttl));
    ExpiringEntry<RawAtom> previous =
        store.compute(
            key,
            (k, existing) -> {
              if (existing != null && !existing.isExpired()) {
                return existing;
              }
              return entry;
            });
    if (previous != entry) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    ExpiringEntry<RawAtom> entry = store.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (entry.isExpired()) {
      store.remove(key, entry);
      return Optional.empty();
    }
    return Optional.of(entry.value());
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    ExpiringEntry<RawAtom> next =
        new ExpiringEntry<>(new RawAtom(value, token), Instant.now().plus(ttl));
    ExpiringEntry<RawAtom> result =
        store.compute(
            key,
            (k, existing) -> {
              if (existing == null || existing.isExpired()) {
                return null;
              }
              return next;
            });
    return result == next;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    ExpiringEntry<RawAtom> result =
        store.compute(
            key,
            (k, existing) -> {
              if (existing == null || existing.isExpired()) {
                return null;
              }
              return new ExpiringEntry<>(existing.value(), Instant.now().plus(ttl));
            });
    return result != null;
  }

  @Override
  public int sweep(int maxToSweep) {
    return ExpiringEntry.sweepExpired(store, maxToSweep);
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }
}
