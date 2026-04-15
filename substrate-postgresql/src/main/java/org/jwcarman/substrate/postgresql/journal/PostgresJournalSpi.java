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
package org.jwcarman.substrate.postgresql.journal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jwcarman.substrate.core.journal.AbstractJournalSpi;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresJournalSpi extends AbstractJournalSpi {

  private static final int TRIM_INTERVAL = 100;

  private final JdbcTemplate jdbcTemplate;
  private final long maxLen;
  private final AtomicLong appendCounter = new AtomicLong(0);

  public PostgresJournalSpi(JdbcTemplate jdbcTemplate, String prefix, long maxLen) {
    super(prefix);
    this.jdbcTemplate = jdbcTemplate;
    this.maxLen = maxLen;
  }

  @Override
  public String append(String key, byte[] data, Duration ttl) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO substrate_journal_entries (key, data, timestamp)"
                + " VALUES (?, ?, NOW()) RETURNING id",
            Long.class,
            key,
            data);

    if (appendCounter.incrementAndGet() % TRIM_INTERVAL == 0) {
      trimOldEntries(key);
    }

    return String.valueOf(id);
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    long cursor = Long.parseLong(afterId);
    return jdbcTemplate.query(
        "SELECT id, key, data, timestamp FROM substrate_journal_entries"
            + " WHERE key = ? AND id > ? ORDER BY id",
        this::mapRow,
        key,
        cursor);
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    List<RawJournalEntry> entries =
        jdbcTemplate.query(
            "SELECT id, key, data, timestamp FROM substrate_journal_entries"
                + " WHERE key = ? ORDER BY id DESC LIMIT ?",
            this::mapRow,
            key,
            count);
    Collections.reverse(entries);
    return entries;
  }

  @Override
  public void complete(String key, Duration retentionTtl) {
    jdbcTemplate.update(
        "INSERT INTO substrate_journal_completed (key) VALUES (?) ON CONFLICT (key) DO NOTHING",
        key);
  }

  @Override
  public boolean isComplete(String key) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_journal_completed WHERE key = ?", Integer.class, key);
    return count != null && count > 0;
  }

  @Override
  public boolean exists(String key) {
    Boolean result =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM substrate_journal_entries WHERE key = ?)"
                + " OR EXISTS(SELECT 1 FROM substrate_journal_completed WHERE key = ?)",
            Boolean.class,
            key,
            key);
    return Boolean.TRUE.equals(result);
  }

  @Override
  public void delete(String key) {
    jdbcTemplate.update("DELETE FROM substrate_journal_entries WHERE key = ?", key);
    jdbcTemplate.update("DELETE FROM substrate_journal_completed WHERE key = ?", key);
  }

  private void trimOldEntries(String key) {
    jdbcTemplate.update(
        "DELETE FROM substrate_journal_entries WHERE key = ? AND id NOT IN"
            + " (SELECT id FROM substrate_journal_entries WHERE key = ? ORDER BY id DESC LIMIT ?)",
        key,
        key,
        maxLen);
  }

  private RawJournalEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new RawJournalEntry(
        String.valueOf(rs.getLong("id")),
        rs.getString("key"),
        rs.getBytes("data"),
        rs.getTimestamp("timestamp").toInstant());
  }
}
