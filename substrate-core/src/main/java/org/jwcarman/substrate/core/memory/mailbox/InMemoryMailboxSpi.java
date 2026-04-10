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
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

public class InMemoryMailboxSpi extends AbstractMailboxSpi {

  private record Entry(Optional<byte[]> value, Instant expiresAt) {
    boolean isAlive(Instant now) {
      return now.isBefore(expiresAt);
    }
  }

  private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

  public InMemoryMailboxSpi() {
    super("substrate:mailbox:");
  }

  @Override
  public void create(String key, Duration ttl) {
    store.put(key, new Entry(Optional.empty(), Instant.now().plus(ttl)));
  }

  @Override
  public void deliver(String key, byte[] value) {
    Instant now = Instant.now();
    store.compute(
        key,
        (k, existing) -> {
          if (existing == null || !existing.isAlive(now)) {
            throw new MailboxExpiredException(key);
          }
          if (existing.value().isPresent()) {
            throw new MailboxFullException(key);
          }
          return new Entry(Optional.of(value), existing.expiresAt());
        });
  }

  @Override
  public Optional<byte[]> get(String key) {
    Entry entry = store.get(key);
    Instant now = Instant.now();
    if (entry == null || !entry.isAlive(now)) {
      if (entry != null) {
        store.remove(key, entry);
      }
      throw new MailboxExpiredException(key);
    }
    return entry.value();
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }
}
