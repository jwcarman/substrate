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
package org.jwcarman.substrate.nats;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.KeyValueConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * Shared helpers for substrate-nats SPI implementations that store state in NATS JetStream KV
 * buckets ({@code NatsAtomSpi}, {@code NatsMailboxSpi}). Centralizes bucket provisioning and key
 * sanitization so the per-primitive SPIs stay focused on their primitive-specific logic.
 */
public final class NatsKvSupport {

  private NatsKvSupport() {}

  /**
   * Ensure a KV bucket with the given name exists, creating it with the supplied default TTL and
   * single-revision history if missing. Idempotent — safe to call on every SPI construction.
   *
   * @param connection an open NATS connection
   * @param bucketName the bucket name
   * @param defaultTtl the bucket-wide default TTL applied at creation time
   * @throws UncheckedIOException if the underlying I/O call fails
   * @throws IllegalStateException if the JetStream API rejects the create call for any reason other
   *     than "bucket already exists"
   */
  public static void ensureBucketExists(
      Connection connection, String bucketName, Duration defaultTtl) {
    try {
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
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create NATS KV bucket", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to create NATS KV bucket", e);
    }
  }

  /**
   * Sanitize a substrate key for use as a NATS KV key. NATS KV keys cannot contain {@code :} or
   * {@code .}; substrate's {@code <prefix>:<primitive>:<name>} key shape uses both, so they are
   * replaced with {@code -}.
   *
   * @param key the substrate key
   * @return a NATS-KV-safe key
   */
  public static String toKvKey(String key) {
    return key.replace(':', '-').replace('.', '-');
  }
}
