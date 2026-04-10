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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.substrate.core.mailbox.AbstractMailboxSpi;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresMailboxSpi extends AbstractMailboxSpi {

  private final JdbcTemplate jdbcTemplate;

  public PostgresMailboxSpi(JdbcTemplate jdbcTemplate, String prefix) {
    super(prefix);
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void create(String key, Duration ttl) {
    Instant expiresAt = Instant.now().plus(ttl);
    jdbcTemplate.update(
        "INSERT INTO substrate_mailbox (key, value, expires_at) VALUES (?, NULL, ?)"
            + " ON CONFLICT (key) DO UPDATE SET value = NULL, expires_at = EXCLUDED.expires_at",
        key,
        Timestamp.from(expiresAt));
  }

  @Override
  public void deliver(String key, byte[] value) {
    int updated =
        jdbcTemplate.update(
            "UPDATE substrate_mailbox SET value = ? WHERE key = ? AND expires_at > NOW()",
            value,
            key);
    if (updated == 0) {
      throw new MailboxExpiredException(key);
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    List<Map<String, Object>> results =
        jdbcTemplate.queryForList(
            "SELECT value FROM substrate_mailbox WHERE key = ? AND expires_at > NOW()", key);
    if (results.isEmpty()) {
      throw new MailboxExpiredException(key);
    }
    byte[] value = (byte[]) results.getFirst().get("value");
    return Optional.ofNullable(value);
  }

  @Override
  public void delete(String key) {
    jdbcTemplate.update("DELETE FROM substrate_mailbox WHERE key = ?", key);
  }
}
