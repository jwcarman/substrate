# `PostgresAtomSpi` — Atom backend for PostgreSQL

**Depends on: spec 018 (Atom primitive) and spec 022 (sweep SPI + core
sweepers) must both be completed first.** This is the first backend
that actually implements the `sweep(int)` method with real work (all
other backends have native TTL and inherit the no-op default).

## What to build

Add an `AtomSpi` implementation to the `substrate-postgresql` module
using an atoms table, `INSERT ... ON CONFLICT DO NOTHING` for atomic
create, row-level filter-on-read for correctness, and a batched
`DELETE ... SKIP LOCKED` for the sweep.

This is the reference implementation for backends without native TTL.

## PostgreSQL primitives used

- **`INSERT ... ON CONFLICT (key) DO NOTHING`** — atomic create. Check
  row count; if zero, the key already existed → throw
  `AtomAlreadyExistsException`.
- **`UPDATE ... WHERE key = ? AND expires_at > now()`** — conditional
  update for `set` and `touch`, only succeeds on live atoms.
- **`SELECT ... WHERE key = ? AND expires_at > now()`** — filter-on-
  read, guarantees correctness regardless of sweep state.
- **`DELETE ... WHERE ctid IN (SELECT ... FOR UPDATE SKIP LOCKED)`** —
  canonical Postgres pattern for concurrent cleanup workers. Multiple
  sweepers on multiple nodes can each grab disjoint batches without
  blocking.
- **`DELETE ... WHERE key = ?`** — unconditional delete for user-
  requested removal.

## Table schema

```sql
CREATE TABLE IF NOT EXISTS substrate_atoms (
  key         text        PRIMARY KEY,
  value       bytea       NOT NULL,
  token       text        NOT NULL,
  expires_at  timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_substrate_atoms_expires_at
  ON substrate_atoms (expires_at);
```

The `expires_at` index is load-bearing: both the filter-on-read in
every query and the sweep `DELETE ... WHERE expires_at < now()` need
it to avoid sequential scans as the table grows.

Table name is configurable; default `substrate_atoms`. The table is
auto-created on first use via `CREATE TABLE IF NOT EXISTS`.

## Files created

```
substrate-postgresql/src/main/java/org/jwcarman/substrate/postgresql/atom/
  PostgresAtomSpi.java
  PostgresAtomAutoConfiguration.java

substrate-postgresql/src/test/java/org/jwcarman/substrate/postgresql/atom/
  PostgresAtomSpiTest.java
  PostgresAtomIT.java
```

## Files modified

- `substrate-postgresql/src/main/java/org/jwcarman/substrate/postgresql/PostgresProperties.java` —
  add nested `AtomProperties(boolean enabled, String tableName)` with
  defaults `(true, "substrate_atoms")`.
- `AutoConfiguration.imports` — register
  `PostgresAtomAutoConfiguration`.
- `substrate-postgresql-defaults.properties` — document new
  properties.

## `PostgresAtomSpi` sketch

```java
package org.jwcarman.substrate.postgresql.atom;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.AtomRecord;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;

public class PostgresAtomSpi extends AbstractAtomSpi {

  private final DataSource dataSource;
  private final String tableName;

  public PostgresAtomSpi(DataSource dataSource, String tableName) {
    super("substrate:atom:");
    this.dataSource = dataSource;
    this.tableName = tableName;
    createTableIfMissing();
  }

  private void createTableIfMissing() {
    try (var conn = dataSource.getConnection();
         var stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS " + tableName + " ("
              + "  key text PRIMARY KEY,"
              + "  value bytea NOT NULL,"
              + "  token text NOT NULL,"
              + "  expires_at timestamptz NOT NULL)");
      stmt.execute(
          "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_expires_at"
              + " ON " + tableName + " (expires_at)");
    } catch (SQLException e) {
      throw new RuntimeException("Failed to create " + tableName, e);
    }
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    String sql = "INSERT INTO " + tableName
        + " (key, value, token, expires_at) VALUES (?, ?, ?, ?)"
        + " ON CONFLICT (key) DO NOTHING";
    try (var conn = dataSource.getConnection();
         var ps = conn.prepareStatement(sql)) {
      ps.setString(1, key);
      ps.setBytes(2, value);
      ps.setString(3, token);
      ps.setTimestamp(4, Timestamp.from(Instant.now().plus(ttl)));
      int rowCount = ps.executeUpdate();
      if (rowCount == 0) {
        throw new AtomAlreadyExistsException(key);
      }
    } catch (SQLException e) {
      throw new RuntimeException("Postgres atom create failed", e);
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    String sql = "SELECT value, token FROM " + tableName
        + " WHERE key = ? AND expires_at > now()";
    try (var conn = dataSource.getConnection();
         var ps = conn.prepareStatement(sql)) {
      ps.setString(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        byte[] bytes = rs.getBytes("value");
        String token = rs.getString("token");
        return Optional.of(new AtomRecord(bytes, token));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Postgres atom read failed", e);
    }
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    String sql = "UPDATE " + tableName
        + " SET value = ?, token = ?, expires_at = ?"
        + " WHERE key = ? AND expires_at > now()";
    try (var conn = dataSource.getConnection();
         var ps = conn.prepareStatement(sql)) {
      ps.setBytes(1, value);
      ps.setString(2, token);
      ps.setTimestamp(3, Timestamp.from(Instant.now().plus(ttl)));
      ps.setString(4, key);
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw new RuntimeException("Postgres atom set failed", e);
    }
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    String sql = "UPDATE " + tableName
        + " SET expires_at = ?"
        + " WHERE key = ? AND expires_at > now()";
    try (var conn = dataSource.getConnection();
         var ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(Instant.now().plus(ttl)));
      ps.setString(2, key);
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw new RuntimeException("Postgres atom touch failed", e);
    }
  }

  @Override
  public void delete(String key) {
    String sql = "DELETE FROM " + tableName + " WHERE key = ?";
    try (var conn = dataSource.getConnection();
         var ps = conn.prepareStatement(sql)) {
      ps.setString(1, key);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Postgres atom delete failed", e);
    }
  }

  @Override
  public int sweep(int maxToSweep) {
    // Canonical Postgres pattern for concurrent cleanup workers.
    // SKIP LOCKED allows N sweepers on N nodes to grab disjoint
    // batches without blocking each other.
    String sql = "DELETE FROM " + tableName
        + " WHERE ctid IN ("
        + "   SELECT ctid FROM " + tableName
        + "    WHERE expires_at < now()"
        + "    ORDER BY expires_at"
        + "    LIMIT ?"
        + "    FOR UPDATE SKIP LOCKED"
        + ")";
    try (var conn = dataSource.getConnection();
         var ps = conn.prepareStatement(sql)) {
      ps.setInt(1, maxToSweep);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Postgres atom sweep failed", e);
    }
  }
}
```

## Auto-configuration

```java
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "substrate.postgresql.atom",
                       name = "enabled",
                       havingValue = "true",
                       matchIfMissing = true)
public class PostgresAtomAutoConfiguration {

  @Bean
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnMissingBean(AtomSpi.class)
  public PostgresAtomSpi postgresAtomSpi(DataSource dataSource, PostgresProperties props) {
    return new PostgresAtomSpi(dataSource, props.atom().tableName());
  }
}
```

## Acceptance criteria

- [ ] `PostgresAtomSpi` implements all `AtomSpi` methods.
- [ ] Constructor auto-creates the table and the `expires_at` index
      via `CREATE ... IF NOT EXISTS`.
- [ ] `create` uses `INSERT ... ON CONFLICT DO NOTHING` and throws
      `AtomAlreadyExistsException` when the affected row count is
      zero.
- [ ] `read` filters on `expires_at > now()` in the WHERE clause.
      Expired rows that haven't been swept yet read as
      `Optional.empty()`.
- [ ] `set` and `touch` include `expires_at > now()` in the WHERE
      clause and return `true` only when the UPDATE affected a row.
- [ ] `delete` is unconditional and idempotent.
- [ ] `sweep(int maxToSweep)` uses the
      `DELETE ... WHERE ctid IN (SELECT ... FOR UPDATE SKIP LOCKED)`
      pattern and returns the affected row count.
- [ ] The sweep query uses `LIMIT ?` bound to `maxToSweep` —
      verified by reading the prepared statement.
- [ ] `PostgresProperties.atom` nested record with `enabled` and
      `tableName` defaults.
- [ ] `PostgresAtomAutoConfiguration` registered in
      `AutoConfiguration.imports`.
- [ ] The existing `Sweeper` bean (from spec 022 / spec 025)
      correctly drives `PostgresAtomSpi.sweep`. Verified by
      integration test:
      1. Write 500 atoms with `Duration.ofMillis(100)` TTL
      2. Wait for expiry
      3. Assert `read()` returns empty for all of them
      4. Wait one sweep interval
      5. Assert the table's row count is 0

- [ ] A concurrent sweep test exists:
      1. Write 5000 expired atoms
      2. Launch 4 threads each calling
         `PostgresAtomSpi.sweep(1000)` in a loop until they return 0
      3. Assert no thread blocked on lock waits (use Awaitility with
         a tight timeout)
      4. Assert total deletes equals 5000 (each row deleted exactly
         once across all threads)

- [ ] `PostgresAtomIT` uses Testcontainers Postgres and exercises
      create/read/set/touch/delete, concurrent create collision,
      TTL expiry via filter-on-read, and the sweep path end-to-end.
- [ ] Apache 2.0 license headers on every new file.
- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`

## Implementation notes

- **SKIP LOCKED is the critical primitive.** Without it, concurrent
  sweepers block each other on row locks. With it, they cooperate
  without any coordination overhead. This is the canonical
  Postgres pattern for job queues and cleanup workers; it's used by
  `pg-boss`, `river`, `good_job`, and every other serious
  Postgres-backed queue.
- **`ctid` in the DELETE WHERE clause** is a tuple identifier,
  faster than matching on the business key for this pattern. The
  inner SELECT grabs locks by ctid, the outer DELETE operates on
  those exact tuples. This avoids a join back to the primary key.
- **`ORDER BY expires_at` in the sweep** ensures FIFO-ish cleanup
  (oldest-first), which prevents newer entries from starving if
  the sweeper is keeping up. Not strictly required but good
  hygiene.
- **`expires_at` column type must be `timestamptz`**, not
  `timestamp`. Postgres's `now()` returns `timestamptz` and mixing
  types forces implicit casts that break index usage.
- **Connection management:** use try-with-resources on every
  `Connection`, `PreparedStatement`, and `ResultSet`. The existing
  `PostgresJournalSpi` / `PostgresMailboxSpi` in the consolidated
  module show the pattern; follow it.
- **DataSource injection:** reuse the existing `DataSource` bean
  in the `substrate-postgresql` module. Don't create a new one.
  The `PostgresAtomAutoConfiguration` should pick up whatever
  DataSource the application or other substrate modules have
  already wired.
- **Clock skew across nodes:** the `now()` in Postgres is the
  database's clock, not any node's wall clock. This means clock
  skew across application nodes is irrelevant for TTL filtering —
  all nodes see the same "now" via the database. This is a
  feature of using the database's clock for timestamp comparisons.
- **The sweep integration test needs to assert that the core
  `Sweeper` bean is actually driving the Postgres sweep.** If the
  test creates a `PostgresAtomSpi` directly without the
  autoconfiguration, it bypasses the sweeper. Use
  `@SpringBootTest` with the application context to verify the
  end-to-end wiring.
- **The existing `substrate-postgresql` module's Journal and
  Mailbox SPIs will also need sweep implementations** — but those
  are separate follow-on specs (or could be bundled with this one
  if you want to knock all three out at once). Recommend
  separate specs for focus.
