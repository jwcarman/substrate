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
package org.jwcarman.substrate.nats.mailbox;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.core.mailbox.AbstractMailboxSpi;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

public class NatsMailboxSpi extends AbstractMailboxSpi {

  private static final byte[] CREATED_MARKER = new byte[] {0};

  private final Connection connection;
  private final String bucketName;
  private final Duration defaultTtl;

  public NatsMailboxSpi(
      Connection connection, String prefix, String bucketName, Duration defaultTtl) {
    super(prefix);
    this.connection = connection;
    this.bucketName = bucketName;
    this.defaultTtl = defaultTtl;
    ensureBucketExists();
  }

  @Override
  public void create(String key, Duration ttl) {
    try {
      var kv = connection.keyValue(bucketName);
      kv.put(toKvKey(key), CREATED_MARKER);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create mailbox in NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to create mailbox in NATS KV", e);
    }
  }

  @Override
  public void deliver(String key, byte[] value) {
    try {
      var kv = connection.keyValue(bucketName);
      KeyValueEntry entry = kv.get(toKvKey(key));
      if (entry == null) {
        throw new MailboxExpiredException(key);
      }
      if (entry.getValue() != null && !java.util.Arrays.equals(entry.getValue(), CREATED_MARKER)) {
        throw new MailboxFullException(key);
      }
      kv.update(toKvKey(key), value, entry.getRevision());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to deliver to NATS KV", e);
    } catch (JetStreamApiException e) {
      // Revision mismatch means another thread delivered concurrently
      throw new MailboxFullException(key);
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    try {
      var kv = connection.keyValue(bucketName);
      KeyValueEntry entry = kv.get(toKvKey(key));
      if (entry == null || entry.getValue() == null) {
        throw new MailboxExpiredException(key);
      }
      if (java.util.Arrays.equals(entry.getValue(), CREATED_MARKER)) {
        return Optional.empty();
      }
      return Optional.of(entry.getValue());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to get from NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to get from NATS KV", e);
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

  private String toKvKey(String key) {
    return key.replace(':', '-').replace('.', '-');
  }
}
