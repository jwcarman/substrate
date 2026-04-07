# Redis backend: Journal, Mailbox, and Notifier

## What to build

Three Maven modules providing Redis-backed implementations of all three Substrate SPIs.
Each is an independent jar. Uses Spring Data Redis with Lettuce client.

### substrate-journal-redis

Package: `org.jwcarman.substrate.journal.redis`

**RedisJournal** extends `AbstractJournal`:
- Uses Redis Streams (XADD, XREAD, XREVRANGE) via Lettuce sync commands
- `append`: XADD with `data` and `timestamp` fields, then EXPIRE for TTL
- `readAfter`: XREAD from the given ID
- `readLast`: XREVRANGE with COUNT, then reverse in code for chronological order
- `complete`: store a completion marker (e.g., SET a `{key}:completed` flag)
- `delete`: DEL the stream key and completion flag
- Key translation: colons are native to Redis, no translation needed

**RedisJournalProperties** — `@ConfigurationProperties(prefix = "substrate.journal.redis")`:
- `prefix` (String, default `"substrate:journal:"`)
- `maxLen` (long, default 100,000)
- `defaultTtl` (Duration, default 1h)

**RedisJournalAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(RedisConnectionFactory.class)`
- Creates `RedisJournal` bean from properties and `RedisConnectionFactory`

### substrate-mailbox-redis

Package: `org.jwcarman.substrate.mailbox.redis`

**RedisMailbox** extends `AbstractMailbox`:
- Uses Redis GET/SET with TTL for value storage
- `deliver`: SET the value with configured TTL, then notify via the Notifier
- `await`: poll or subscribe for the key, return CompletableFuture that resolves
  when the value appears or times out
- `delete`: DEL the key
- Key translation: colons native, no translation

**RedisMailboxProperties** — `@ConfigurationProperties(prefix = "substrate.mailbox.redis")`:
- `prefix` (String, default `"substrate:mailbox:"`)
- `defaultTtl` (Duration, default 5m)

**RedisMailboxAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(RedisConnectionFactory.class)`
- Creates `RedisMailbox` bean

### substrate-notifier-redis

Package: `org.jwcarman.substrate.notifier.redis`

**RedisNotifier** — mirrors Odyssey's `RedisOdysseyStreamNotifier`:
- Extends `RedisPubSubAdapter<String, String>`
- Implements `SmartLifecycle`
- Pattern subscription via PSUBSCRIBE on `{channelPrefix}*`
- `notify`: PUBLISH to `{channelPrefix}{key}` with payload
- `subscribe`: add handler to `CopyOnWriteArrayList`

**RedisNotifierProperties** — `@ConfigurationProperties(prefix = "substrate.notifier.redis")`:
- `channelPrefix` (String, default `"substrate:notify:"`)

**RedisNotifierAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(RedisConnectionFactory.class)`
- Creates `RedisNotifier` bean

## Acceptance criteria

- [ ] All three modules compile and produce separate jars
- [ ] Each module has its own `AutoConfiguration.imports` registration
- [ ] Each auto-config runs before `SubstrateAutoConfiguration` (suppresses in-memory fallback)
- [ ] Properties load defaults from `*-defaults.properties` files
- [ ] Properties are overridable via `application.properties`
- [ ] Unit tests with mocked Lettuce commands verify: append/read/delete for Journal,
  deliver/await/delete for Mailbox, publish/subscribe for Notifier
- [ ] Integration tests with Testcontainers Redis verify full lifecycle for each SPI
- [ ] Auto-configuration tests verify bean creation with `ApplicationContextRunner`
- [ ] Journal key generation uses configured prefix (default `substrate:journal:`)
- [ ] Mailbox key generation uses configured prefix (default `substrate:mailbox:`)
- [ ] Notifier channel uses configured prefix (default `substrate:notify:`)
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Modules added to `substrate-bom`
- [ ] Modules added to parent POM module list

## Implementation notes

- Reference Odyssey's Redis modules:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-eventlog-redis/`
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-notifier-redis/`
- Odyssey uses three separate prefixes (ephemeral/channel/broadcast). Substrate uses one
  prefix per SPI — simpler because Substrate doesn't know about stream flavors.
- Redis Streams are the right data structure for Journal (ordered, cursor-based).
- Redis GET/SET + TTL is the right pattern for Mailbox (single value, auto-expire).
- The Notifier pattern (Pub/Sub + SmartLifecycle) carries over directly from Odyssey.
- Testcontainers: use `redis:7-alpine` image.
