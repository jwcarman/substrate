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
package org.jwcarman.substrate.nats.atom;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueOperation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.jwcarman.substrate.nats.NatsKvSupport;

public class NatsAtomSpi extends AbstractAtomSpi {

  private final Connection connection;
  private final String bucketName;

  public NatsAtomSpi(Connection connection, String prefix, String bucketName, Duration defaultTtl) {
    super(prefix);
    this.connection = connection;
    this.bucketName = bucketName;
    NatsKvSupport.ensureBucketExists(connection, bucketName, defaultTtl);
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    try {
      var kv = connection.keyValue(bucketName);
      kv.create(toKvKey(key), encode(value, token));
    } catch (JetStreamApiException e) {
      if (isKeyExists(e)) {
        throw new AtomAlreadyExistsException(key);
      }
      throw new IllegalStateException("Failed to create atom in NATS KV", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create atom in NATS KV", e);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    try {
      var kv = connection.keyValue(bucketName);
      KeyValueEntry entry = kv.get(toKvKey(key));
      if (entry == null || entry.getOperation() != KeyValueOperation.PUT) {
        return Optional.empty();
      }
      return Optional.of(decode(entry.getValue()));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read atom from NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to read atom from NATS KV", e);
    }
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    try {
      var kv = connection.keyValue(bucketName);
      KeyValueEntry entry = kv.get(toKvKey(key));
      if (entry == null || entry.getOperation() != KeyValueOperation.PUT) {
        return false;
      }
      kv.put(toKvKey(key), encode(value, token));
      return true;
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to set atom in NATS KV", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to set atom in NATS KV", e);
    }
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    try {
      var kv = connection.keyValue(bucketName);
      KeyValueEntry entry = kv.get(toKvKey(key));
      if (entry == null || entry.getOperation() != KeyValueOperation.PUT) {
        return false;
      }
      kv.put(toKvKey(key), entry.getValue());
      return true;
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to touch atom in NATS KV", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to touch atom in NATS KV", e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      var kv = connection.keyValue(bucketName);
      kv.delete(toKvKey(key));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete atom from NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to delete atom from NATS KV", e);
    }
  }

  @Override
  public boolean exists(String key) {
    try {
      var kv = connection.keyValue(bucketName);
      KeyValueEntry entry = kv.get(toKvKey(key));
      return entry != null && entry.getOperation() == KeyValueOperation.PUT;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to check atom existence in NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to check atom existence in NATS KV", e);
    }
  }

  private static String toKvKey(String key) {
    return NatsKvSupport.toKvKey(key);
  }

  private static boolean isKeyExists(JetStreamApiException e) {
    return e.getApiErrorCode() == 10071;
  }

  static byte[] encode(byte[] value, String token) {
    byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(4 + tokenBytes.length + value.length);
    buffer.putInt(tokenBytes.length);
    buffer.put(tokenBytes);
    buffer.put(value);
    return buffer.array();
  }

  static RawAtom decode(byte[] payload) {
    ByteBuffer buffer = ByteBuffer.wrap(payload);
    int tokenLen = buffer.getInt();
    byte[] tokenBytes = new byte[tokenLen];
    buffer.get(tokenBytes);
    byte[] value = new byte[buffer.remaining()];
    buffer.get(value);
    return new RawAtom(value, new String(tokenBytes, StandardCharsets.UTF_8));
  }
}
