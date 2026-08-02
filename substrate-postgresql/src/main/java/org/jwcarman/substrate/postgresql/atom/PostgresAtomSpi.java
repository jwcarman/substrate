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
package org.jwcarman.substrate.postgresql.atom;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.CasResult;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresAtomSpi extends AbstractAtomSpi {

  private final JdbcTemplate jdbcTemplate;

  public PostgresAtomSpi(JdbcTemplate jdbcTemplate, String prefix) {
    super(prefix);
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    int rowCount =
        jdbcTemplate.update(
            "INSERT INTO substrate_atom (key, value, token, expires_at) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (key) DO NOTHING",
            key,
            value,
            token,
            Timestamp.from(Instant.now().plus(ttl)));
    if (rowCount == 0) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    List<Map<String, Object>> results =
        jdbcTemplate.queryForList(
            "SELECT value, token FROM substrate_atom WHERE key = ? AND expires_at > NOW()", key);
    if (results.isEmpty()) {
      return Optional.empty();
    }
    byte[] value = (byte[]) results.getFirst().get("value");
    String token = (String) results.getFirst().get("token");
    return Optional.of(new RawAtom(value, token));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    return jdbcTemplate.update(
            "UPDATE substrate_atom SET value = ?, token = ?, expires_at = ?"
                + " WHERE key = ? AND expires_at > NOW()",
            value,
            token,
            Timestamp.from(Instant.now().plus(ttl)),
            key)
        > 0;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The guarded {@code UPDATE} is exact: it commits only when the stored token still matches, so
   * no lost update is possible. Classifying a <em>failed</em> update is approximate under the
   * default {@code READ COMMITTED} isolation level — a concurrent {@code DELETE} landing between
   * the CTE's two legs can leave the presence probe non-empty while the update matched nothing,
   * reporting {@link CasResult#TOKEN_MISMATCH} where {@link CasResult#ABSENT} is truer. Both
   * outcomes mean the caller lost the race.
   */
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "WITH current AS ("
                + " SELECT token FROM substrate_atom WHERE key = ? AND expires_at > NOW()"
                + "), updated AS ("
                + " UPDATE substrate_atom SET value = ?, token = ?, expires_at = ?"
                + " WHERE key = ? AND expires_at > NOW() AND token = ?"
                + " RETURNING 1"
                + ") SELECT (SELECT COUNT(*) FROM updated) AS updated_count,"
                + " (SELECT COUNT(*) FROM current) AS present_count",
            key,
            value,
            newToken,
            Timestamp.from(Instant.now().plus(ttl)),
            key,
            expectedToken);
    if (((Number) row.get("updated_count")).longValue() > 0) {
      return CasResult.COMMITTED;
    }
    return ((Number) row.get("present_count")).longValue() > 0
        ? CasResult.TOKEN_MISMATCH
        : CasResult.ABSENT;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    return jdbcTemplate.update(
            "UPDATE substrate_atom SET expires_at = ? WHERE key = ? AND expires_at > NOW()",
            Timestamp.from(Instant.now().plus(ttl)),
            key)
        > 0;
  }

  @Override
  public void delete(String key) {
    jdbcTemplate.update("DELETE FROM substrate_atom WHERE key = ?", key);
  }

  @Override
  public boolean exists(String key) {
    Boolean result =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM substrate_atom WHERE key = ? AND expires_at > NOW())",
            Boolean.class,
            key);
    return Boolean.TRUE.equals(result);
  }

  @Override
  public int sweep(int maxToSweep) {
    // SKIP LOCKED allows concurrent sweepers on multiple nodes to grab
    // disjoint batches without blocking each other.
    return jdbcTemplate.update(
        "DELETE FROM substrate_atom WHERE ctid IN ("
            + " SELECT ctid FROM substrate_atom"
            + " WHERE expires_at < NOW()"
            + " ORDER BY expires_at"
            + " LIMIT ?"
            + " FOR UPDATE SKIP LOCKED"
            + ")",
        maxToSweep);
  }
}
