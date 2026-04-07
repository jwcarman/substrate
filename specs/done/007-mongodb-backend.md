# MongoDB backend: Journal and Mailbox

## What to build

Two Maven modules providing MongoDB-backed implementations of Journal and Mailbox.
Uses Spring Data MongoDB (`MongoTemplate`). No Notifier — MongoDB Change Streams could
work but have latency concerns (deferred per PRD).

### substrate-journal-mongodb

Package: `org.jwcarman.substrate.journal.mongodb`

**MongoDbJournal** extends `AbstractJournal`:
- Uses `MongoTemplate` for BSON document operations
- Collection: configurable name (default `"substrate_journal"`)
- Document schema: `entryId` (String), `key` (String), `data` (String),
  `timestamp` (Instant), `expireAt` (Instant for TTL index)
- Indexes: compound `(key, entryId)` for cursor queries, TTL index on `expireAt`
  with `expire(0)` (MongoDB deletes when `expireAt` is in the past)
- `append`: insert document with `expireAt = Instant.now().plus(ttl)`
- `readAfter`: `Query` with `Criteria.where("key").is(key).and("entryId").gt(afterId)`,
  sorted by `entryId` ascending
- `readLast`: query sorted by `entryId` descending with limit, then reverse
- `complete`: insert a completion marker document or use a separate collection
- `delete`: `remove` all documents matching `key`
- Index auto-creation on first use

**MongoDbJournalProperties** — `@ConfigurationProperties(prefix = "substrate.journal.mongodb")`:
- `prefix` (String, default `"substrate:journal:"`)
- `collectionName` (String, default `"substrate_journal"`)
- `ttl` (Duration, default 24h)

**MongoDbJournalAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(MongoTemplate.class)`
- Creates `MongoDbJournal` bean from `MongoTemplate` and properties

### substrate-mailbox-mongodb

Package: `org.jwcarman.substrate.mailbox.mongodb`

**MongoDbMailbox** extends `AbstractMailbox`:
- Uses `MongoTemplate`
- Collection: configurable name (default `"substrate_mailbox"`)
- Document schema: `key` (String, unique index), `value` (String),
  `expireAt` (Instant for TTL index)
- `deliver`: upsert document with `expireAt = Instant.now().plus(ttl)`
- `await`: find by key — if present, return immediately; otherwise poll with backoff
  until value appears or timeout expires
- `delete`: remove by key
- TTL index on `expireAt` for automatic cleanup

**MongoDbMailboxProperties** — `@ConfigurationProperties(prefix = "substrate.mailbox.mongodb")`:
- `prefix` (String, default `"substrate:mailbox:"`)
- `collectionName` (String, default `"substrate_mailbox"`)
- `defaultTtl` (Duration, default 5m)

**MongoDbMailboxAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(MongoTemplate.class)`
- Creates `MongoDbMailbox` bean

## Acceptance criteria

- [ ] Both modules compile and produce separate jars
- [ ] Each module has its own `AutoConfiguration.imports` registration
- [ ] Each auto-config suppresses the in-memory fallback for its SPI
- [ ] Properties load defaults from `*-defaults.properties` files
- [ ] Unit tests with mocked `MongoTemplate` verify insert/query/remove operations
- [ ] Integration tests with Testcontainers MongoDB (`mongo:7`) verify full lifecycle:
  - Journal: append, readAfter, readLast, complete, delete, TTL index creation
  - Mailbox: deliver-then-await, await-then-deliver, delete, TTL index creation
- [ ] Indexes are auto-created (compound + TTL)
- [ ] All keys use configured prefix
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Modules added to `substrate-bom` and parent POM

## Implementation notes

- Reference Odyssey's MongoDB module:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-eventlog-mongodb/`
- MongoDB TTL indexes delete documents when the indexed datetime field is past.
  Setting `expire(0)` means "delete immediately when `expireAt` passes."
- Mailbox `await` uses polling. A future spec could add a MongoDB Change Streams-based
  Notifier for event-driven wake-up.
- Testcontainers: use `mongo:7` image.
