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
package org.jwcarman.substrate.mailbox.postgresql;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.spi.AbstractMailboxSpi;
import org.jwcarman.substrate.spi.Notifier;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresMailboxSpi extends AbstractMailboxSpi {

  private final JdbcTemplate jdbcTemplate;
  private final Notifier notifier;
  private final ConcurrentMap<String, CompletableFuture<String>> pending =
      new ConcurrentHashMap<>();

  public PostgresMailboxSpi(JdbcTemplate jdbcTemplate, Notifier notifier, String prefix) {
    super(prefix);
    this.jdbcTemplate = jdbcTemplate;
    this.notifier = notifier;
    this.notifier.subscribe(this::onNotification);
  }

  @Override
  public void deliver(String key, String value) {
    jdbcTemplate.update(
        "INSERT INTO substrate_mailbox (key, value) VALUES (?, ?)"
            + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, created_at = NOW()",
        key,
        value);
    notifier.notify(key, value);
  }

  @Override
  public CompletableFuture<String> await(String key, Duration timeout) {
    String existing = lookupValue(key);
    if (existing != null) {
      return CompletableFuture.completedFuture(existing);
    }
    CompletableFuture<String> future = pending.computeIfAbsent(key, k -> new CompletableFuture<>());
    // Double-check in case deliver() was called between our lookup and computeIfAbsent
    String deliveredAfter = lookupValue(key);
    if (deliveredAfter != null) {
      future.complete(deliveredAfter);
      pending.remove(key);
    }
    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void delete(String key) {
    jdbcTemplate.update("DELETE FROM substrate_mailbox WHERE key = ?", key);
    CompletableFuture<String> future = pending.remove(key);
    if (future != null) {
      future.cancel(false);
    }
  }

  private String lookupValue(String key) {
    List<String> results =
        jdbcTemplate.queryForList(
            "SELECT value FROM substrate_mailbox WHERE key = ?", String.class, key);
    return results.isEmpty() ? null : results.getFirst();
  }

  private void onNotification(String key, String payload) {
    CompletableFuture<String> future = pending.remove(key);
    if (future != null) {
      future.complete(payload);
    }
  }
}
