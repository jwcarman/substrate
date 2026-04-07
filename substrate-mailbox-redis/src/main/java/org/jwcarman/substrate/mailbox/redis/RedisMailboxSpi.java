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
package org.jwcarman.substrate.mailbox.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.spi.AbstractMailboxSpi;
import org.jwcarman.substrate.spi.Notifier;

public class RedisMailboxSpi extends AbstractMailboxSpi {

  private final RedisCommands<String, String> commands;
  private final Notifier notifier;
  private final Duration defaultTtl;
  private final ConcurrentMap<String, CompletableFuture<byte[]>> pending =
      new ConcurrentHashMap<>();

  public RedisMailboxSpi(
      RedisCommands<String, String> commands,
      Notifier notifier,
      String prefix,
      Duration defaultTtl) {
    super(prefix);
    this.commands = commands;
    this.notifier = notifier;
    this.defaultTtl = defaultTtl;
    this.notifier.subscribe(this::onNotification);
  }

  @Override
  public void deliver(String key, byte[] value) {
    SetArgs setArgs = SetArgs.Builder.ex(defaultTtl.toSeconds());
    commands.set(key, Base64.getEncoder().encodeToString(value), setArgs);
    notifier.notify(key, key);
  }

  @Override
  public CompletableFuture<byte[]> await(String key, Duration timeout) {
    byte[] existing = getValueFromRedis(key);
    if (existing != null) {
      return CompletableFuture.completedFuture(existing);
    }
    CompletableFuture<byte[]> future = pending.computeIfAbsent(key, k -> new CompletableFuture<>());
    // Double-check in case deliver() was called between our get and computeIfAbsent
    byte[] deliveredAfter = getValueFromRedis(key);
    if (deliveredAfter != null) {
      future.complete(deliveredAfter);
      pending.remove(key);
    }
    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void delete(String key) {
    commands.del(key);
    CompletableFuture<byte[]> future = pending.remove(key);
    if (future != null) {
      future.cancel(false);
    }
  }

  private byte[] getValueFromRedis(String key) {
    String encoded = commands.get(key);
    return encoded != null ? Base64.getDecoder().decode(encoded) : null;
  }

  private void onNotification(String key, String payload) {
    CompletableFuture<byte[]> future = pending.remove(key);
    if (future != null) {
      byte[] value = getValueFromRedis(key);
      if (value != null) {
        future.complete(value);
      }
    }
  }
}
