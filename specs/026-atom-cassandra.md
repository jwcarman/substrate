# `CassandraAtomSpi` — Atom backend for Cassandra

**Depends on: spec 018 (Atom primitive) must be completed first.** Spec 018
establishes the `AtomSpi` contract; this spec implements that contract
using Cassandra as the backend.

## What to build

Add an `AtomSpi` implementation to the `substrate-cassandra` module,
using Cassandra's Lightweight Transactions (LWT) for atomic
set-if-not-exists and the native `TTL` keyword for expiry.

## Cassandra primitives used

- **`INSERT ... IF NOT EXISTS`** — LWT providing atomic create. Returns
  `applied=true` on success and `applied=false` with the existing row
  on collision.
- **`UPDATE ... USING TTL`** — per-row TTL, applied on both insert and
  update. Cassandra physically removes rows when TTL expires
  (tombstones written immediately, actual deletion during compaction).
- **`UPDATE ... IF EXISTS`** — conditional update to implement `set`
  and `touch` only when the row is still alive.
- **`DELETE ... IF EXISTS`** — conditional delete; no-op if already
  absent.

Cassandra handles TTL natively. `CassandraAtomSpi.sweep(int)` inherits
the no-op default from `AbstractAtomSpi`.

## Schema

```cql
CREATE TABLE IF NOT EXISTS substrate.atoms (
  key       text PRIMARY KEY,
  value     blob,
  token     text
);
```

- **Keyspace** — `substrate` (or configurable prefix via properties).
- **Primary key** — the atom's backend-qualified key.
- **No `expires_at` column** — TTL is per-row via the CQL `USING TTL`
  clause; the row physically vanishes on expiry.

The table name and keyspace are configurable. The keyspace must be
pre-created by the operator (substrate does not `CREATE KEYSPACE`);
the table is auto-created on first use.

## Files created

```
substrate-cassandra/src/main/java/org/jwcarman/substrate/cassandra/atom/
  CassandraAtomSpi.java
  CassandraAtomAutoConfiguration.java

substrate-cassandra/src/test/java/org/jwcarman/substrate/cassandra/atom/
  CassandraAtomSpiTest.java          (unit or lightweight integration)
  CassandraAtomIT.java                (testcontainer integration)
```

## Files modified

- `substrate-cassandra/src/main/java/org/jwcarman/substrate/cassandra/CassandraProperties.java` —
  add a nested `AtomProperties(boolean enabled, String tableName)`
  record with defaults `(true, "atoms")`.
- `substrate-cassandra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` —
  register `CassandraAtomAutoConfiguration`.
- `substrate-cassandra/src/main/resources/substrate-cassandra-defaults.properties` —
  document new properties with defaults.

## `CassandraAtomSpi` sketch

```java
package org.jwcarman.substrate.cassandra.atom;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.AtomRecord;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;

public class CassandraAtomSpi extends AbstractAtomSpi {

  private final CqlSession session;
  private final String tableName;
  private final PreparedStatement insertIfNotExists;
  private final PreparedStatement selectByKey;
  private final PreparedStatement updateIfExists;
  private final PreparedStatement touchIfExists;
  private final PreparedStatement deleteIfExists;

  public CassandraAtomSpi(CqlSession session, String keyspace, String tableName) {
    super("substrate:atom:");
    this.session = session;
    this.tableName = tableName;
    // CREATE TABLE IF NOT EXISTS ...
    // Prepare statements:
    this.insertIfNotExists = session.prepare(
        "INSERT INTO " + keyspace + "." + tableName
            + " (key, value, token) VALUES (?, ?, ?) IF NOT EXISTS USING TTL ?");
    this.selectByKey = session.prepare(
        "SELECT value, token FROM " + keyspace + "." + tableName + " WHERE key = ?");
    this.updateIfExists = session.prepare(
        "UPDATE " + keyspace + "." + tableName
            + " USING TTL ? SET value = ?, token = ? WHERE key = ? IF EXISTS");
    this.touchIfExists = session.prepare(
        "UPDATE " + keyspace + "." + tableName
            + " USING TTL ? SET token = token WHERE key = ? IF EXISTS");
    this.deleteIfExists = session.prepare(
        "DELETE FROM " + keyspace + "." + tableName + " WHERE key = ? IF EXISTS");
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    Row row = session.execute(
        insertIfNotExists.bind(key, ByteBuffer.wrap(value), token, (int) ttl.toSeconds())
    ).one();
    if (row != null && !row.getBoolean("[applied]")) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    Row row = session.execute(selectByKey.bind(key)).one();
    if (row == null) return Optional.empty();
    ByteBuffer bb = row.getByteBuffer("value");
    byte[] bytes = new byte[bb.remaining()];
    bb.get(bytes);
    return Optional.of(new AtomRecord(bytes, row.getString("token")));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    Row row = session.execute(
        updateIfExists.bind((int) ttl.toSeconds(), ByteBuffer.wrap(value), token, key)
    ).one();
    return row != null && row.getBoolean("[applied]");
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    // "UPDATE ... USING TTL ... SET token = token" is a no-op write that
    // re-applies the TTL. Cassandra resets the row's TTL on any UPDATE
    // with USING TTL.
    Row row = session.execute(touchIfExists.bind((int) ttl.toSeconds(), key)).one();
    return row != null && row.getBoolean("[applied]");
  }

  @Override
  public void delete(String key) {
    session.execute(deleteIfExists.bind(key));
  }
}
```

**Note on `touch`:** Cassandra's TTL is per-column, not per-row. The
self-assignment `SET token = token` is the canonical way to reset the
TTL on an existing row — it re-writes the `token` column with a fresh
TTL without changing its value. We do the same for `value` in `set`,
which also gets a fresh TTL. Because all columns in the row are
written with the same TTL on every `create` / `set` / `touch`, the
row's effective expiry is consistent.

## Auto-configuration

```java
@AutoConfiguration
@ConditionalOnClass(CqlSession.class)
@ConditionalOnProperty(prefix = "substrate.cassandra.atom",
                       name = "enabled",
                       havingValue = "true",
                       matchIfMissing = true)
public class CassandraAtomAutoConfiguration {

  @Bean
  @ConditionalOnBean(CqlSession.class)
  @ConditionalOnMissingBean(AtomSpi.class)
  public CassandraAtomSpi cassandraAtomSpi(CqlSession session, CassandraProperties props) {
    return new CassandraAtomSpi(session, props.keyspace(), props.atom().tableName());
  }
}
```

## Acceptance criteria

- [ ] `CassandraAtomSpi` implements all `AtomSpi` methods per spec 018's
      contract.
- [ ] `create` uses `INSERT ... IF NOT EXISTS` and throws
      `AtomAlreadyExistsException` when `[applied]` is false.
- [ ] `set` and `touch` use `IF EXISTS` so they return `false` for
      atoms that have expired (TTL elapsed → row vanishes).
- [ ] `read` returns `Optional.empty()` for expired/absent keys.
- [ ] `delete` uses `IF EXISTS` and is idempotent.
- [ ] TTL is applied via CQL `USING TTL <seconds>` on every write;
      passed `Duration` is converted to whole seconds.
- [ ] `CassandraAtomSpi.sweep(int)` inherits the `return 0` no-op from
      `AbstractAtomSpi` (Cassandra handles expiry natively).
- [ ] `CassandraProperties` has a new `atom` subproperty with
      `enabled: true` default and a configurable `tableName` default
      `atoms`.
- [ ] `CassandraAtomAutoConfiguration` is registered via
      `AutoConfiguration.imports` and gated by
      `@ConditionalOnProperty(matchIfMissing=true)`.
- [ ] Disabling via `substrate.cassandra.atom.enabled=false` prevents
      the bean from being created. Verified by a `@SpringBootTest`.
- [ ] `CassandraAtomIT` integration test uses a Testcontainers
      Cassandra instance and exercises create/read/set/touch/delete,
      concurrent-create collision (exactly one wins), and TTL expiry
      (with Awaitility).
- [ ] Apache 2.0 license headers on every new file.
- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify` (including the IT — IT
      runs via Testcontainers).

## Implementation notes

- Cassandra TTL is measured in **seconds** (not milliseconds). Convert
  `Duration.toSeconds()` and cast to `int`. A Duration less than 1
  second rounds down to 0, which disables TTL in Cassandra — guard
  against this at the core layer via `SubstrateProperties` max-TTL
  enforcement and a minimum of 1 second inside the SPI
  implementation.
- LWT (`IF NOT EXISTS`) is expensive — it requires Paxos rounds.
  That's acceptable for create (infrequent, safety-critical) but
  we use plain `UPDATE ... IF EXISTS` for `set` / `touch` rather
  than LWT-ing every write. The `IF EXISTS` is still a lightweight
  Paxos round, but it's the minimum needed to detect "row vanished
  via TTL."
- The `CassandraJournalSpi` in the consolidated `substrate-cassandra`
  module already uses LWTs for some operations — follow the same
  coding patterns it uses (prepared statements, `session` injection,
  error handling).
- `CqlSession` is the modern DataStax driver 4.x API. Make sure the
  existing Cassandra module already uses it and not the legacy 3.x
  `Session`.
- For the `tableName` configurability: allow a fully-qualified
  `<keyspace>.<table>` or a simple name that uses the configured
  keyspace. Simpler path: require a plain table name and always use
  `keyspace + "." + tableName` in prepared statements.
