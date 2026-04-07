# DynamoDB backend: Journal and Mailbox

## What to build

Two Maven modules providing DynamoDB-backed implementations of Journal and Mailbox.
Uses `software.amazon.awssdk:dynamodb` (AWS SDK v2). No Notifier — DynamoDB has no
native pub/sub (consumers pair with an external Notifier like SNS or NATS).

### substrate-journal-dynamodb

Package: `org.jwcarman.substrate.journal.dynamodb`

**DynamoDbJournal** extends `AbstractJournal`:
- Uses `DynamoDbClient` (low-level API, no enhanced client)
- Table schema: hash key `key` (S), range key `entry_id` (S), attributes `data` (S),
  `timestamp` (S as ISO-8601), `ttl` (N as epoch seconds for DynamoDB TTL)
- `append`: `PutItem` with generated entry ID and TTL
- `readAfter`: `Query` with `key = ? AND entry_id > ?`, `scanIndexForward=true`,
  paginated via `exclusiveStartKey`
- `readLast`: `Query` with `scanIndexForward=false` and `limit`, then reverse
- `complete`: `PutItem` a completion marker item (e.g., `entry_id = "COMPLETED"`)
- `delete`: batch `DeleteItem` in chunks of 25 (DynamoDB batch limit)
- Table auto-creation when `autoCreateTable=true`

**DynamoDbJournalProperties** — `@ConfigurationProperties(prefix = "substrate.journal.dynamodb")`:
- `prefix` (String, default `"substrate:journal:"`)
- `tableName` (String, default `"substrate_journal"`)
- `autoCreateTable` (boolean, default true)
- `ttl` (Duration, default 24h)

**DynamoDbJournalAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(DynamoDbClient.class)`
- Creates `DynamoDbJournal` bean from `DynamoDbClient` and properties

### substrate-mailbox-dynamodb

Package: `org.jwcarman.substrate.mailbox.dynamodb`

**DynamoDbMailbox** extends `AbstractMailbox`:
- Table schema: hash key `key` (S), attributes `value` (S),
  `ttl` (N as epoch seconds for DynamoDB TTL)
- `deliver`: `PutItem` with value and TTL
- `await`: `GetItem` — if present, return immediately; otherwise poll with backoff
  until value appears or timeout expires
- `delete`: `DeleteItem`
- Table auto-creation when `autoCreateTable=true`

**DynamoDbMailboxProperties** — `@ConfigurationProperties(prefix = "substrate.mailbox.dynamodb")`:
- `prefix` (String, default `"substrate:mailbox:"`)
- `tableName` (String, default `"substrate_mailbox"`)
- `autoCreateTable` (boolean, default true)
- `defaultTtl` (Duration, default 5m)

**DynamoDbMailboxAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(DynamoDbClient.class)`
- Creates `DynamoDbMailbox` bean

## Acceptance criteria

- [ ] Both modules compile and produce separate jars
- [ ] Each module has its own `AutoConfiguration.imports` registration
- [ ] Each auto-config suppresses the in-memory fallback for its SPI
- [ ] Properties load defaults from `*-defaults.properties` files
- [ ] Unit tests with mocked `DynamoDbClient` verify PutItem/Query/DeleteItem operations
- [ ] Integration tests with Testcontainers LocalStack (DynamoDB) verify full lifecycle:
  - Journal: append, readAfter with pagination, readLast, complete, delete (batch)
  - Mailbox: deliver-then-await, await-then-deliver, delete
- [ ] Table auto-creation works when enabled
- [ ] TTL field is correctly populated as epoch seconds
- [ ] Batch delete correctly handles >25 items
- [ ] All keys use configured prefix
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Modules added to `substrate-bom` and parent POM

## Implementation notes

- Reference Odyssey's DynamoDB module:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-eventlog-dynamodb/`
- DynamoDB TTL is eventually consistent (items may persist ~48h after expiry). This is
  fine — TTL is a cleanup mechanism, not a hard guarantee.
- Mailbox `await` uses polling because DynamoDB Streams would add too much complexity
  for a simple single-value use case. The Notifier (from another backend) handles the
  wake-up signal.
- Testcontainers: use `localstack/localstack:latest` with DynamoDB service.
