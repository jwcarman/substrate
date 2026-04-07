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
package org.jwcarman.substrate.memory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.spi.AbstractMailbox;

public class InMemoryMailbox extends AbstractMailbox {

  private final ConcurrentMap<String, CompletableFuture<String>> pending =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, String> delivered = new ConcurrentHashMap<>();

  public InMemoryMailbox() {
    super("substrate:mailbox:");
  }

  @Override
  public void deliver(String key, String value) {
    delivered.put(key, value);
    CompletableFuture<String> future = pending.remove(key);
    if (future != null) {
      future.complete(value);
    }
  }

  @Override
  public CompletableFuture<String> await(String key, Duration timeout) {
    String existing = delivered.get(key);
    if (existing != null) {
      return CompletableFuture.completedFuture(existing);
    }
    CompletableFuture<String> future = pending.computeIfAbsent(key, k -> new CompletableFuture<>());
    // Check again in case deliver() was called between our get and computeIfAbsent
    String deliveredAfter = delivered.get(key);
    if (deliveredAfter != null) {
      future.complete(deliveredAfter);
    }
    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void delete(String key) {
    delivered.remove(key);
    CompletableFuture<String> future = pending.remove(key);
    if (future != null) {
      future.cancel(false);
    }
  }
}
