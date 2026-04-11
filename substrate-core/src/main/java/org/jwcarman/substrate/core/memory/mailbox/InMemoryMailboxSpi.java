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
package org.jwcarman.substrate.core.memory.mailbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jwcarman.substrate.core.mailbox.AbstractMailboxSpi;
import org.jwcarman.substrate.core.memory.ExpiringEntry;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

public class InMemoryMailboxSpi extends AbstractMailboxSpi {

  private final ConcurrentMap<String, ExpiringEntry<Optional<byte[]>>> store =
      new ConcurrentHashMap<>();

  public InMemoryMailboxSpi() {
    super("substrate:mailbox:");
  }

  @Override
  public void create(String key, Duration ttl) {
    store.put(key, new ExpiringEntry<>(Optional.empty(), Instant.now().plus(ttl)));
  }

  @Override
  public void deliver(String key, byte[] value) {
    store.compute(
        key,
        (k, existing) -> {
          if (existing == null || existing.isExpired()) {
            throw new MailboxExpiredException(key);
          }
          if (existing.value().isPresent()) {
            throw new MailboxFullException(key);
          }
          return new ExpiringEntry<>(Optional.of(value), existing.expiresAt());
        });
  }

  @Override
  public Optional<byte[]> get(String key) {
    ExpiringEntry<Optional<byte[]>> entry = store.get(key);
    if (entry == null || entry.isExpired()) {
      if (entry != null) {
        store.remove(key, entry);
      }
      throw new MailboxExpiredException(key);
    }
    return entry.value();
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
