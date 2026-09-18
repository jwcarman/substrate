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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jwcarman.substrate.core.journal.AbstractJournalSpi;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.jwcarman.substrate.journal.JournalAlreadyExistsException;
import org.jwcarman.substrate.journal.JournalCompletedException;
import org.jwcarman.substrate.journal.JournalExpiredException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL-backed {@link org.jwcarman.substrate.core.journal.JournalSpi}.
 *
 * <p>PostgreSQL has no native record expiry, so both of a journal's clocks are kept as explicit
 * deadline columns and reclaimed by {@link #sweep(int)}:
 *
 * <ul>
 *   <li>the journal's own lease lives in {@code substrate_journal.dies_at} — the inactivity
 *       deadline while the journal is active, pushed forward by every {@link #append append}, and
 *       the retention deadline once {@link #complete complete} is called;
 *   <li>each entry's lease lives in {@code substrate_journal_entries.expires_at}.
 * </ul>
 *
 * <p>A {@code NULL} deadline means "no TTL" — the same meaning {@code Duration.ZERO} carries on the
 * Cassandra, Redis, MongoDB and DynamoDB journal SPIs. Past-deadline rows stop being visible
 * immediately, because every read is gated on the deadline, and are physically removed by the next
 * sweep. A stopped sweeper therefore delays reclamation but never resurrects expired data.
 */
public class PostgresJournalSpi extends AbstractJournalSpi {

  private static final int TRIM_INTERVAL = 100;

  /** Restricts a query to entries that have not passed their entry TTL. */
  private static final String LIVE_ENTRY = " AND (expires_at IS NULL OR expires_at > NOW())";

  /** Restricts a query to journals that have not passed their lease. */
  private static final String LIVE_JOURNAL = " (dies_at IS NULL OR dies_at > NOW())";

  private final JdbcTemplate jdbcTemplate;
  private final long maxLen;
  private final AtomicLong appendCounter = new AtomicLong(0);

  public PostgresJournalSpi(JdbcTemplate jdbcTemplate, String prefix, long maxLen) {
    super(prefix);
    this.jdbcTemplate = jdbcTemplate;
    this.maxLen = maxLen;
  }

  @Override
  public void create(String key, Duration inactivityTtl) {
    int created =
        jdbcTemplate.update(
            "INSERT INTO substrate_journal (key, inactivity_ttl_millis, dies_at, completed)"
                + " VALUES (?, ?, ?, FALSE)"
                + " ON CONFLICT (key) DO UPDATE SET"
                + " inactivity_ttl_millis = EXCLUDED.inactivity_ttl_millis,"
                + " dies_at = EXCLUDED.dies_at,"
                + " completed = FALSE"
                + " WHERE substrate_journal.dies_at IS NOT NULL"
                + " AND substrate_journal.dies_at <= NOW()",
            key,
            millisOf(inactivityTtl),
            deadlineOf(inactivityTtl));

    if (created == 0) {
      throw new JournalAlreadyExistsException(key);
    }

    // Replacing a dead journal must not leave its entries behind for the new one to read.
    jdbcTemplate.update("DELETE FROM substrate_journal_entries WHERE key = ?", key);
  }

  @Override
  public String append(String key, byte[] data, Duration entryTtl) {
    int touched =
        jdbcTemplate.update(
            "UPDATE substrate_journal SET dies_at = CASE WHEN inactivity_ttl_millis > 0"
                + " THEN NOW() + (inactivity_ttl_millis * INTERVAL '1 millisecond')"
                + " ELSE NULL END"
                + " WHERE key = ? AND NOT completed AND"
                + LIVE_JOURNAL,
            key);

    if (touched == 0) {
      throw notAppendable(key);
    }

    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO substrate_journal_entries (key, data, timestamp, expires_at)"
                + " VALUES (?, ?, NOW(), ?) RETURNING id",
            Long.class,
            key,
            data,
            deadlineOf(entryTtl));

    if (appendCounter.incrementAndGet() % TRIM_INTERVAL == 0) {
      trimOldEntries(key);
    }

    return String.valueOf(id);
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    requireLive(key);
    long cursor = Long.parseLong(afterId);
    return jdbcTemplate.query(
        "SELECT id, key, data, timestamp FROM substrate_journal_entries"
            + " WHERE key = ? AND id > ?"
            + LIVE_ENTRY
            + " ORDER BY id",
        this::mapRow,
        key,
        cursor);
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    requireLive(key);
    List<RawJournalEntry> entries =
        jdbcTemplate.query(
            "SELECT id, key, data, timestamp FROM substrate_journal_entries"
                + " WHERE key = ?"
                + LIVE_ENTRY
                + " ORDER BY id DESC LIMIT ?",
            this::mapRow,
            key,
            count);
    Collections.reverse(entries);
    return entries;
  }

  @Override
  public void complete(String key, Duration retentionTtl) {
    int completed =
        jdbcTemplate.update(
            "UPDATE substrate_journal SET completed = TRUE, dies_at = ?"
                + " WHERE key = ? AND"
                + LIVE_JOURNAL,
            deadlineOf(retentionTtl),
            key);

    if (completed == 0) {
      throw new JournalExpiredException(key);
    }
  }

  @Override
  public boolean isComplete(String key) {
    Boolean result =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM substrate_journal WHERE key = ? AND completed AND"
                + LIVE_JOURNAL
                + ")",
            Boolean.class,
            key);
    return Boolean.TRUE.equals(result);
  }

  @Override
  public boolean exists(String key) {
    Boolean result =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM substrate_journal WHERE key = ? AND" + LIVE_JOURNAL + ")",
            Boolean.class,
            key);
    return Boolean.TRUE.equals(result);
  }

  @Override
  public void delete(String key) {
    jdbcTemplate.update("DELETE FROM substrate_journal_entries WHERE key = ?", key);
    jdbcTemplate.update("DELETE FROM substrate_journal WHERE key = ?", key);
  }

  @Override
  public int sweep(int maxToSweep) {
    int swept = sweepDeadJournals(maxToSweep);
    if (swept < maxToSweep) {
      swept += sweepExpiredEntries(maxToSweep - swept);
    }
    return swept;
  }

  /**
   * Removes journals whose lease has run out, along with every entry they still hold. Entries of a
   * dead journal are unreachable whatever their own TTL says, so they go with it.
   */
  private int sweepDeadJournals(int maxToSweep) {
    // SKIP LOCKED allows concurrent sweepers on multiple nodes to grab
    // disjoint batches without blocking each other.
    List<String> keys =
        jdbcTemplate.queryForList(
            "DELETE FROM substrate_journal WHERE ctid IN ("
                + " SELECT ctid FROM substrate_journal"
                + " WHERE dies_at IS NOT NULL AND dies_at < NOW()"
                + " ORDER BY dies_at"
                + " LIMIT ?"
                + " FOR UPDATE SKIP LOCKED"
                + ") RETURNING key",
            String.class,
            maxToSweep);

    for (String key : keys) {
      jdbcTemplate.update("DELETE FROM substrate_journal_entries WHERE key = ?", key);
    }
    return keys.size();
  }

  /** Removes entries that have outlived their own TTL inside a journal that is still alive. */
  private int sweepExpiredEntries(int maxToSweep) {
    return jdbcTemplate.update(
        "DELETE FROM substrate_journal_entries WHERE ctid IN ("
            + " SELECT ctid FROM substrate_journal_entries"
            + " WHERE expires_at IS NOT NULL AND expires_at < NOW()"
            + " ORDER BY expires_at"
            + " LIMIT ?"
            + " FOR UPDATE SKIP LOCKED"
            + ")",
        maxToSweep);
  }

  /**
   * Fails a read whose journal has outlived its lease. A journal that was never created — or one
   * the sweeper has already reclaimed — is reported as absent rather than expired, leaving the
   * caller with an empty read.
   */
  private void requireLive(String key) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT" + LIVE_JOURNAL + " AS live FROM substrate_journal WHERE key = ?", key);
    if (!rows.isEmpty() && !Boolean.TRUE.equals(rows.getFirst().get("live"))) {
      throw new JournalExpiredException(key);
    }
  }

  /**
   * Explains why an append could not land: a live but completed journal rejects the append, and
   * anything else means the journal is gone or past its lease.
   */
  private RuntimeException notAppendable(String key) {
    if (isComplete(key)) {
      return new JournalCompletedException(key);
    }
    return new JournalExpiredException(key);
  }

  private void trimOldEntries(String key) {
    jdbcTemplate.update(
        "DELETE FROM substrate_journal_entries WHERE key = ? AND id NOT IN"
            + " (SELECT id FROM substrate_journal_entries WHERE key = ? ORDER BY id DESC LIMIT ?)",
        key,
        key,
        maxLen);
  }

  /**
   * Resolves a TTL to an absolute deadline, reading {@code null} and {@code Duration.ZERO} as
   * "store this without a TTL" — the same meaning the Cassandra, Redis, MongoDB and DynamoDB
   * journal SPIs give it.
   */
  private static Timestamp deadlineOf(Duration ttl) {
    if (ttl == null || ttl.isZero()) {
      return null;
    }
    return Timestamp.from(Instant.now().plus(ttl));
  }

  private static long millisOf(Duration ttl) {
    return ttl == null ? 0L : ttl.toMillis();
  }

  private RawJournalEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new RawJournalEntry(
        String.valueOf(rs.getLong("id")),
        rs.getString("key"),
        rs.getBytes("data"),
        rs.getTimestamp("timestamp").toInstant());
  }
}
