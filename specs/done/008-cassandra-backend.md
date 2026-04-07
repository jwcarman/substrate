# Cassandra backend: Journal only

## What to build

One Maven module providing a Cassandra-backed Journal implementation. Uses the DataStax
Java Driver (`com.datastax.oss:java-driver-core`). No Mailbox or Notifier — Cassandra
doesn't have native pub/sub or a clean single-value-with-TTL pattern for Mailbox.

### substrate-journal-cassandra

Package: `org.jwcarman.substrate.journal.cassandra`

**CassandraJournal** extends `AbstractJournal`:
- Uses `CqlSession` with prepared statements
- Table: configurable name (default `substrate_journal`)
- Schema: `key TEXT`, `entry_id TIMEUUID`, `data TEXT`, `timestamp TIMESTAMP`,
  `PRIMARY KEY (key, entry_id)` with `CLUSTERING ORDER BY (entry_id ASC)`
- Event ID: `Uuids.timeBased()` (TIMEUUID — time-ordered, globally unique)
- `append`: `INSERT INTO ... VALUES (?, ?, ?, ?) USING TTL ?` when TTL > 0
- `readAfter`: `SELECT ... WHERE key = ? AND entry_id > ? ORDER BY entry_id ASC`
- `readLast`: `SELECT ... WHERE key = ? ORDER BY entry_id DESC LIMIT ?`, then reverse
- `complete`: insert a completion marker row (e.g., sentinel `entry_id` or separate table)
- `delete`: `DELETE FROM ... WHERE key = ?`
- Schema auto-creation when `autoCreateSchema=true`
- Prepared statement caching for performance

**CassandraJournalProperties** — `@ConfigurationProperties(prefix = "substrate.journal.cassandra")`:
- `prefix` (String, default `"substrate:journal:"`)
- `tableName` (String, default `"substrate_journal"`)
- `autoCreateSchema` (boolean, default true)
- `defaultTtl` (Duration, default 24h — 0 means no TTL)

**CassandraJournalAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(CqlSession.class)`
- Creates `CassandraJournal` bean from `CqlSession` and properties

## Acceptance criteria

- [ ] Module compiles and produces a jar
- [ ] `AutoConfiguration.imports` registration exists
- [ ] Auto-config suppresses the in-memory Journal fallback
- [ ] Properties load defaults from `*-defaults.properties`
- [ ] Unit tests with mocked `CqlSession` verify CQL operations and prepared statements
- [ ] Integration tests with Testcontainers Cassandra verify full lifecycle:
  - append, readAfter (cursor with TIMEUUID), readLast, complete, delete
  - TTL behavior (entries inserted with USING TTL)
  - Prepared statement reuse
- [ ] Auto-configuration tests verify bean creation
- [ ] TIMEUUID IDs are time-ordered and globally unique
- [ ] All keys use configured prefix
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Module added to `substrate-bom` and parent POM

## Implementation notes

- Reference Odyssey's Cassandra module:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-eventlog-cassandra/`
- Cassandra uses TIMEUUID for natural time ordering — this replaces the UUID v7
  approach used by other backends. The `AbstractJournal.generateEntryId()` is NOT used;
  this implementation generates its own IDs via `Uuids.timeBased()`.
- TTL is applied at insert time via `USING TTL`. Cassandra's TTL is per-cell/row and
  is exact (unlike DynamoDB's eventual cleanup).
- Testcontainers: use `cassandra:4` image. Note Cassandra containers are slow to start.
