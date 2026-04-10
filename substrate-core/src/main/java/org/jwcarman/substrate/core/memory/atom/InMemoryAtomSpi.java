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
import org.jwcarman.substrate.core.atom.AtomRecord;

public class InMemoryAtomSpi extends AbstractAtomSpi {

  private record Entry(byte[] value, String token, Instant expiresAt) {
    boolean isAlive(Instant now) {
      return now.isBefore(expiresAt);
    }
  }

  private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

  public InMemoryAtomSpi() {
    super("substrate:atom:");
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    Entry entry = new Entry(value, token, Instant.now().plus(ttl));
    Entry previous =
        store.compute(
            key,
            (k, existing) -> {
              if (existing != null && existing.isAlive(Instant.now())) {
                return existing;
              }
              return entry;
            });
    if (previous != entry) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    Entry entry = store.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (!entry.isAlive(Instant.now())) {
      store.remove(key, entry);
      return Optional.empty();
    }
    return Optional.of(new AtomRecord(entry.value(), entry.token()));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    Instant now = Instant.now();
    Entry next = new Entry(value, token, now.plus(ttl));
    Entry result =
        store.compute(
            key,
            (k, existing) -> {
              if (existing == null || !existing.isAlive(now)) {
                return null;
              }
              return next;
            });
    return result == next;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    Instant now = Instant.now();
    Entry result =
        store.compute(
            key,
            (k, existing) -> {
              if (existing == null || !existing.isAlive(now)) {
                return null;
              }
              return new Entry(existing.value(), existing.token(), now.plus(ttl));
            });
    return result != null;
  }

  @Override
  public int sweep(int maxToSweep) {
    Instant now = Instant.now();
    int removed = 0;
    var iterator = store.entrySet().iterator();
    while (iterator.hasNext() && removed < maxToSweep) {
      if (!iterator.next().getValue().isAlive(now)) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }
}
