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
package org.jwcarman.substrate.mailbox.hazelcast;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.spi.AbstractMailboxSpi;
import org.jwcarman.substrate.spi.Notifier;

public class HazelcastMailboxSpi extends AbstractMailboxSpi {

  private final IMap<String, byte[]> map;
  private final Notifier notifier;
  private final Duration defaultTtl;

  public HazelcastMailboxSpi(
      HazelcastInstance hazelcastInstance,
      Notifier notifier,
      String prefix,
      String mapName,
      Duration defaultTtl) {
    super(prefix);
    this.map = hazelcastInstance.getMap(mapName);
    this.notifier = notifier;
    this.defaultTtl = defaultTtl;
  }

  @Override
  public void deliver(String key, byte[] value) {
    map.put(key, value, defaultTtl.toMillis(), TimeUnit.MILLISECONDS);
    notifier.notify(key, key);
  }

  @Override
  public CompletableFuture<byte[]> await(String key, Duration timeout) {
    byte[] existing = map.get(key);
    if (existing != null) {
      return CompletableFuture.completedFuture(existing);
    }

    CompletableFuture<byte[]> future = new CompletableFuture<>();

    MailboxEntryListener listener = new MailboxEntryListener(key, future);
    UUID listenerId = map.addEntryListener(listener, key, true);

    // Double-check in case deliver() was called between our get and addEntryListener
    byte[] deliveredAfter = map.get(key);
    if (deliveredAfter != null) {
      future.complete(deliveredAfter);
    }

    return future
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((result, ex) -> map.removeEntryListener(listenerId));
  }

  @Override
  public void delete(String key) {
    map.remove(key);
  }

  private static class MailboxEntryListener
      implements EntryAddedListener<String, byte[]>, EntryUpdatedListener<String, byte[]> {

    private final String key;
    private final CompletableFuture<byte[]> future;

    MailboxEntryListener(String key, CompletableFuture<byte[]> future) {
      this.key = key;
      this.future = future;
    }

    @Override
    public void entryAdded(EntryEvent<String, byte[]> event) {
      if (key.equals(event.getKey())) {
        future.complete(event.getValue());
      }
    }

    @Override
    public void entryUpdated(EntryEvent<String, byte[]> event) {
      if (key.equals(event.getKey())) {
        future.complete(event.getValue());
      }
    }
  }
}
