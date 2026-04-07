# Hazelcast backend: Journal, Mailbox, and Notifier

## What to build

Three Maven modules providing Hazelcast-backed implementations of all three Substrate SPIs.
Each is an independent jar.

### substrate-journal-hazelcast

Package: `org.jwcarman.substrate.journal.hazelcast`

**HazelcastJournal** extends `AbstractJournal`:
- Uses Hazelcast `Ringbuffer` (one per journal key)
- Jackson for serialization of journal entries to/from JSON
- `append`: serialize entry to JSON, add to ringbuffer
- `readAfter`: `readManyAsync()` from the sequence after the given ID
- `readLast`: read from `tailSequence - count` to `tailSequence`
- `complete`: store completion marker in an `IMap`
- `delete`: destroy the ringbuffer
- Key translation: colons kept as-is (Hazelcast named data structures accept any string)
- Configurable ringbuffer capacity and TTL

**HazelcastJournalProperties** — `@ConfigurationProperties(prefix = "substrate.journal.hazelcast")`:
- `prefix` (String, default `"substrate:journal:"`)
- `ringbufferCapacity` (int, default 100,000)
- `ringbufferTtl` (Duration, default 1h)

**HazelcastJournalAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(HazelcastInstance.class)`
- Creates `HazelcastJournal` bean from `HazelcastInstance` and properties

### substrate-mailbox-hazelcast

Package: `org.jwcarman.substrate.mailbox.hazelcast`

**HazelcastMailbox** extends `AbstractMailbox`:
- Uses Hazelcast `IMap<String, String>` with TTL
- `deliver`: `map.put(key, value, ttl, TimeUnit)` then notify
- `await`: check map, if absent register an `EntryAddedListener` on the IMap,
  return CompletableFuture that resolves on entry or times out
- `delete`: `map.remove(key)`

**HazelcastMailboxProperties** — `@ConfigurationProperties(prefix = "substrate.mailbox.hazelcast")`:
- `prefix` (String, default `"substrate:mailbox:"`)
- `mapName` (String, default `"substrate-mailbox"`)
- `defaultTtl` (Duration, default 5m)

**HazelcastMailboxAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(HazelcastInstance.class)`
- Creates `HazelcastMailbox` bean

### substrate-notifier-hazelcast

Package: `org.jwcarman.substrate.notifier.hazelcast`

**HazelcastNotifier** — mirrors Odyssey's `HazelcastOdysseyStreamNotifier`:
- Uses Hazelcast `ITopic<String>`
- `notify`: `topic.publish(key + "|" + payload)` (pipe-delimited)
- `subscribe`: `topic.addMessageListener()`, parse message, invoke handlers
- Registration UUID tracking for cleanup

**HazelcastNotifierProperties** — `@ConfigurationProperties(prefix = "substrate.notifier.hazelcast")`:
- `topicName` (String, default `"substrate-notify"`)

**HazelcastNotifierAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(HazelcastInstance.class)`
- Creates `HazelcastNotifier` bean

## Acceptance criteria

- [ ] All three modules compile and produce separate jars
- [ ] Each module has its own `AutoConfiguration.imports` registration
- [ ] Each auto-config suppresses the in-memory fallback
- [ ] Properties load defaults from `*-defaults.properties` files
- [ ] Unit tests with mocked `HazelcastInstance` verify operations
- [ ] Integration tests with embedded Hazelcast (`Hazelcast.newHazelcastInstance()`)
  verify full lifecycle for each SPI
- [ ] Auto-configuration tests verify bean creation
- [ ] All keys use configured prefix
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Modules added to `substrate-bom` and parent POM

## Implementation notes

- Reference Odyssey's Hazelcast modules:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-eventlog-hazelcast/`
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-notifier-hazelcast/`
- No Testcontainers needed — Hazelcast runs embedded in-process for tests.
- Mailbox is new. Hazelcast's `IMap` with TTL is a natural fit. The `EntryAddedListener`
  provides event-driven wake-up for `await` without polling.
- Jackson is needed for Journal (ringbuffer stores byte[]/String), but Mailbox and
  Notifier store plain strings.
