# PostgreSQL backend: Journal, Mailbox, and Notifier

## What to build

Three Maven modules providing PostgreSQL-backed implementations of all three Substrate SPIs.
Uses Spring JDBC (no ORM). Each is an independent jar.

### substrate-journal-postgresql

Package: `org.jwcarman.substrate.journal.postgresql`

**PostgresJournal** extends `AbstractJournal`:
- JDBC-based, uses `JdbcTemplate`
- Table: `substrate_journal_entries` with columns: `id BIGSERIAL PRIMARY KEY`,
  `key VARCHAR(512) NOT NULL`, `data TEXT NOT NULL`,
  `timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- Index: `(key, id)` for efficient cursor queries
- `append`: `INSERT ... RETURNING id`
- `readAfter`: `SELECT ... WHERE key = ? AND id > ? ORDER BY id ASC`
- `readLast`: `SELECT ... WHERE key = ? ORDER BY id DESC LIMIT ?`, then reverse
- `complete`: store completion marker in a separate `substrate_journal_completed` table
  or a status column
- `delete`: `DELETE FROM substrate_journal_entries WHERE key = ?`
- Periodic trim: every Nth append, delete oldest entries beyond `maxLen`
- Schema auto-creation via `ResourceDatabasePopulator` when `autoCreateSchema=true`

**PostgresJournalProperties** — `@ConfigurationProperties(prefix = "substrate.journal.postgresql")`:
- `prefix` (String, default `"substrate:journal:"`)
- `maxLen` (long, default 100,000)
- `autoCreateSchema` (boolean, default true)
- `tableName` (String, default `"substrate_journal_entries"`)

**PostgresJournalAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(DataSource.class)`
- Creates `PostgresJournal` bean from `DataSource` and properties

### substrate-mailbox-postgresql

Package: `org.jwcarman.substrate.mailbox.postgresql`

**PostgresMailbox** extends `AbstractMailbox`:
- Table: `substrate_mailbox` with columns: `key VARCHAR(512) PRIMARY KEY`,
  `value TEXT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `deliver`: `INSERT` (or `UPSERT`) the value
- `await`: poll the table or use LISTEN/NOTIFY for wake-up, return CompletableFuture
- `delete`: `DELETE FROM substrate_mailbox WHERE key = ?`
- Schema auto-creation when `autoCreateSchema=true`

**PostgresMailboxProperties** — `@ConfigurationProperties(prefix = "substrate.mailbox.postgresql")`:
- `prefix` (String, default `"substrate:mailbox:"`)
- `autoCreateSchema` (boolean, default true)
- `tableName` (String, default `"substrate_mailbox"`)

**PostgresMailboxAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(DataSource.class)`
- Creates `PostgresMailbox` bean

### substrate-notifier-postgresql

Package: `org.jwcarman.substrate.notifier.postgresql`

**PostgresNotifier** — mirrors Odyssey's `PostgresOdysseyStreamNotifier`:
- Uses PostgreSQL LISTEN/NOTIFY protocol
- Virtual thread for listener loop polling `PGConnection.getNotifications()`
- `notify`: `NOTIFY {channel}, '{key}|{payload}'` (pipe-delimited)
- `subscribe`: add handler to `CopyOnWriteArrayList`
- Implements `SmartLifecycle`
- Channel name validation (regex for valid PostgreSQL identifiers)
- Reconnection with backoff on connection loss

**PostgresNotifierProperties** — `@ConfigurationProperties(prefix = "substrate.notifier.postgresql")`:
- `channel` (String, default `"substrate_notify"`)
- `pollTimeout` (Duration, default 500ms)

**PostgresNotifierAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(DataSource.class)`
- Creates `PostgresNotifier` bean

## Acceptance criteria

- [ ] All three modules compile and produce separate jars
- [ ] Each module has its own `AutoConfiguration.imports` registration
- [ ] Each auto-config suppresses the in-memory fallback
- [ ] Properties load defaults from `*-defaults.properties` files
- [ ] Schema auto-creation works when enabled
- [ ] Unit tests with mocked `JdbcTemplate` verify SQL operations
- [ ] Integration tests with Testcontainers PostgreSQL (`postgres:16-alpine`) verify:
  - Journal: full lifecycle (append, readAfter, readLast, complete, delete, trim)
  - Mailbox: deliver-then-await, await-then-deliver, delete
  - Notifier: LISTEN/NOTIFY delivery, multiple handlers, reconnection
- [ ] Auto-configuration tests verify bean creation
- [ ] All keys use configured prefix
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Modules added to `substrate-bom` and parent POM

## Implementation notes

- Reference Odyssey's PostgreSQL modules:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-eventlog-postgresql/`
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-notifier-postgresql/`
- The Notifier LISTEN/NOTIFY pattern carries over directly from Odyssey. The payload
  format changes from `streamKey|eventId` to `key|payload` (generic).
- Mailbox is new — simple table with upsert semantics. Can use LISTEN/NOTIFY internally
  to wake up the `await` future instead of polling.
- PostgreSQL identifiers can't contain colons, so the channel name must be a simple
  identifier (e.g., `substrate_notify`). The key itself is passed in the payload.
