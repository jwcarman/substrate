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

import java.util.List;
import java.util.Optional;
import org.jwcarman.substrate.spi.AbstractMailboxSpi;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresMailboxSpi extends AbstractMailboxSpi {

  private final JdbcTemplate jdbcTemplate;

  public PostgresMailboxSpi(JdbcTemplate jdbcTemplate, String prefix) {
    super(prefix);
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void deliver(String key, byte[] value) {
    jdbcTemplate.update(
        "INSERT INTO substrate_mailbox (key, value) VALUES (?, ?)"
            + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, created_at = NOW()",
        key,
        value);
  }

  @Override
  public Optional<byte[]> get(String key) {
    List<byte[]> results =
        jdbcTemplate.queryForList(
            "SELECT value FROM substrate_mailbox WHERE key = ?", byte[].class, key);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
  }

  @Override
  public void delete(String key) {
    jdbcTemplate.update("DELETE FROM substrate_mailbox WHERE key = ?", key);
  }
}
