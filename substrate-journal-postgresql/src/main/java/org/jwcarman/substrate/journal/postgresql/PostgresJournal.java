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
package org.jwcarman.substrate.journal.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.jwcarman.substrate.spi.AbstractJournal;
import org.jwcarman.substrate.spi.JournalEntry;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresJournal extends AbstractJournal {

  private static final int TRIM_INTERVAL = 100;

  private final JdbcTemplate jdbcTemplate;
  private final long maxLen;
  private final AtomicLong appendCounter = new AtomicLong(0);

  public PostgresJournal(JdbcTemplate jdbcTemplate, String prefix, long maxLen) {
    super(prefix);
    this.jdbcTemplate = jdbcTemplate;
    this.maxLen = maxLen;
  }

  @Override
  public String append(String key, String data) {
    return append(key, data, null);
  }

  @Override
  public String append(String key, String data, Duration ttl) {
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
  public Stream<JournalEntry> readAfter(String key, String afterId) {
    long cursor = Long.parseLong(afterId);
    List<JournalEntry> entries =
        jdbcTemplate.query(
            "SELECT id, key, data, timestamp FROM substrate_journal_entries"
                + " WHERE key = ? AND id > ? ORDER BY id",
            this::mapRow,
            key,
            cursor);
    return entries.stream();
  }

  @Override
  public Stream<JournalEntry> readLast(String key, int count) {
    List<JournalEntry> entries =
        jdbcTemplate.query(
            "SELECT id, key, data, timestamp FROM substrate_journal_entries"
                + " WHERE key = ? ORDER BY id DESC LIMIT ?",
            this::mapRow,
            key,
            count);
    Collections.reverse(entries);
    return entries.stream();
  }

  @Override
  public void complete(String key) {
    jdbcTemplate.update(
        "INSERT INTO substrate_journal_completed (key) VALUES (?) ON CONFLICT (key) DO NOTHING",
        key);
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

  private JournalEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new JournalEntry(
        String.valueOf(rs.getLong("id")),
        rs.getString("key"),
        rs.getString("data"),
        rs.getTimestamp("timestamp").toInstant());
  }
}
