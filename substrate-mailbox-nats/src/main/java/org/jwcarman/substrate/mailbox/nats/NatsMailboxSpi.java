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
package org.jwcarman.substrate.mailbox.nats;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.impl.NatsKeyValueWatchSubscription;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.spi.AbstractMailboxSpi;
import org.jwcarman.substrate.spi.Notifier;

public class NatsMailboxSpi extends AbstractMailboxSpi {

  private final Connection connection;
  private final Notifier notifier;
  private final String bucketName;
  private final Duration defaultTtl;

  public NatsMailboxSpi(
      Connection connection,
      Notifier notifier,
      String prefix,
      String bucketName,
      Duration defaultTtl) {
    super(prefix);
    this.connection = connection;
    this.notifier = notifier;
    this.bucketName = bucketName;
    this.defaultTtl = defaultTtl;
    ensureBucketExists();
  }

  @Override
  public void deliver(String key, String value) {
    try {
      var kv = connection.keyValue(bucketName);
      kv.put(toKvKey(key), value.getBytes(StandardCharsets.UTF_8));
      notifier.notify(key, value);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to deliver to NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to deliver to NATS KV", e);
    }
  }

  @Override
  public CompletableFuture<String> await(String key, Duration timeout) {
    try {
      var kv = connection.keyValue(bucketName);
      String kvKey = toKvKey(key);

      // Check if value already exists
      KeyValueEntry existing = kv.get(kvKey);
      if (existing != null) {
        return CompletableFuture.completedFuture(
            new String(existing.getValue(), StandardCharsets.UTF_8));
      }

      CompletableFuture<String> future = new CompletableFuture<>();

      NatsKeyValueWatchSubscription watcher =
          kv.watch(
              kvKey,
              new io.nats.client.api.KeyValueWatcher() {
                @Override
                public void watch(KeyValueEntry entry) {
                  if (entry != null && entry.getValue() != null) {
                    future.complete(new String(entry.getValue(), StandardCharsets.UTF_8));
                  }
                }

                @Override
                public void endOfData() {
                  // Initial values processed
                }
              },
              io.nats.client.api.KeyValueWatchOption.UPDATES_ONLY);

      // Double-check in case deliver() was called between our get and watch
      KeyValueEntry deliveredAfter = kv.get(kvKey);
      if (deliveredAfter != null) {
        future.complete(new String(deliveredAfter.getValue(), StandardCharsets.UTF_8));
      }

      return future
          .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
          .whenComplete((result, ex) -> watcher.unsubscribe());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to await from NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to await from NATS KV", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      var kv = connection.keyValue(bucketName);
      kv.delete(toKvKey(key));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete from NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to delete from NATS KV", e);
    }
  }

  private void ensureBucketExists() {
    try {
      createBucketIfMissing();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create NATS KV bucket", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to create NATS KV bucket", e);
    }
  }

  private void createBucketIfMissing() throws IOException, JetStreamApiException {
    var kvm = connection.keyValueManagement();
    try {
      kvm.getStatus(bucketName);
    } catch (JetStreamApiException _) {
      kvm.create(
          KeyValueConfiguration.builder()
              .name(bucketName)
              .ttl(defaultTtl)
              .maxHistoryPerKey(1)
              .build());
    }
  }

  // NATS KV keys cannot contain colons or dots — use dashes
  private String toKvKey(String key) {
    return key.replace(':', '-').replace('.', '-');
  }
}
