# NATS backend: Journal, Mailbox, and Notifier

## What to build

Three Maven modules providing NATS-backed implementations of all three Substrate SPIs.
Uses `io.nats:jnats` client. Each is an independent jar.

### substrate-journal-nats

Package: `org.jwcarman.substrate.journal.nats`

**NatsJournal** extends `AbstractJournal`:
- Uses NATS JetStream for durable, ordered message storage
- Subject format: translate colons to dots (NATS subject hierarchy convention).
  E.g., key `substrate:journal:channel:foo` becomes subject `substrate.journal.channel.foo`
- `append`: JetStream publish to subject, return sequence number from `PublishAck`
- `readAfter`: pull subscription with `DeliverPolicy.ByStartSequence(afterId + 1)`,
  fetch batch up to 1000
- `readLast`: pull subscription with `DeliverPolicy.Last` or sequence math
- `complete`: publish a sentinel/completion message or store in KV
- `delete`: purge the subject via `JetStreamManagement`
- Stream auto-creation with configurable `maxAge` and `maxMessages`

**NatsJournalProperties** — `@ConfigurationProperties(prefix = "substrate.journal.nats")`:
- `prefix` (String, default `"substrate:journal:"`)
- `streamName` (String, default `"substrate-journal"`)
- `maxAge` (Duration, default 24h)
- `maxMessages` (long, default 100,000)

**NatsJournalAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(Connection.class)` (io.nats.client.Connection)
- Creates `NatsJournal` bean from `Connection` and properties

### substrate-mailbox-nats

Package: `org.jwcarman.substrate.mailbox.nats`

**NatsMailbox** extends `AbstractMailbox`:
- Uses NATS KV Store (key-value bucket built on JetStream)
- `deliver`: `kv.put(key, value)` then notify
- `await`: `kv.get(key)` — if present, return immediately; otherwise use a KV watcher
  to wait for the key, return CompletableFuture
- `delete`: `kv.delete(key)`
- Bucket auto-creation with configurable TTL

**NatsMailboxProperties** — `@ConfigurationProperties(prefix = "substrate.mailbox.nats")`:
- `prefix` (String, default `"substrate:mailbox:"`)
- `bucketName` (String, default `"substrate-mailbox"`)
- `defaultTtl` (Duration, default 5m)

**NatsMailboxAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(Connection.class)`
- Creates `NatsMailbox` bean

### substrate-notifier-nats

Package: `org.jwcarman.substrate.notifier.nats`

**NatsNotifier** — mirrors Odyssey's `NatsOdysseyStreamNotifier`:
- Uses NATS Core (not JetStream) for fire-and-forget pub/sub
- Subject format: translate colons to dots. E.g., `substrate:notify:foo` becomes
  `substrate.notify.foo`
- `notify`: publish payload to subject
- `subscribe`: `Dispatcher.subscribe()` with wildcard `>` pattern on prefix subject
- Implements `SmartLifecycle`
- Bidirectional subject translation (dots back to colons for handler callbacks)

**NatsNotifierProperties** — `@ConfigurationProperties(prefix = "substrate.notifier.nats")`:
- `subjectPrefix` (String, default `"substrate:notify:"`)

**NatsNotifierAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(Connection.class)`
- Creates `NatsNotifier` bean

## Acceptance criteria

- [ ] All three modules compile and produce separate jars
- [ ] Each module has its own `AutoConfiguration.imports` registration
- [ ] Each auto-config suppresses the in-memory fallback
- [ ] Properties load defaults from `*-defaults.properties` files
- [ ] Key-to-subject translation correctly converts colons to dots and back
- [ ] Unit tests with mocked NATS `Connection` / `JetStream` verify operations
- [ ] Integration tests with Testcontainers NATS verify full lifecycle for each SPI
- [ ] Auto-configuration tests verify bean creation
- [ ] JetStream stream and KV bucket are auto-created when missing
- [ ] All keys use configured prefix
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Modules added to `substrate-bom` and parent POM

## Implementation notes

- Reference Odyssey's NATS modules:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-eventlog-nats/`
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-notifier-nats/`
- The colon-to-dot translation is critical for NATS — dots are subject hierarchy
  separators. The `>` wildcard matches all sub-subjects.
- NATS KV Store is built on JetStream and is a natural fit for Mailbox. It supports
  watching for key changes, which avoids polling.
- Testcontainers: use `nats:latest` image with JetStream enabled (`-js` flag).
