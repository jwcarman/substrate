# RabbitMQ backend: Journal and Notifier

## What to build

Two Maven modules providing RabbitMQ-backed implementations of Journal and Notifier.
No Mailbox — RabbitMQ is a message broker, not a key-value store, so single-value
store/retrieve doesn't fit cleanly.

### substrate-journal-rabbitmq

Package: `org.jwcarman.substrate.journal.rabbitmq`

**RabbitMqJournal** extends `AbstractJournal`:
- Uses RabbitMQ Streams client (`com.rabbitmq:stream-client`) — not classic queues
- RabbitMQ Streams provide ordered, replayable, log-like semantics (perfect for Journal)
- One named stream per journal key
- `append`: publish message to stream via `Producer`, entry data as body, confirm via
  `CountDownLatch`
- `readAfter`: `Consumer` with `OffsetSpecification` starting after the given offset,
  collect messages into `BlockingQueue` with timeout
- `readLast`: consume from tail offset
- `complete`: publish a sentinel/completion message
- `delete`: delete the stream
- Stream auto-creation with configurable `maxAge` and `maxLengthBytes`
- Producer pool (`ConcurrentHashMap` cache per stream key)

**RabbitMqJournalProperties** — `@ConfigurationProperties(prefix = "substrate.journal.rabbitmq")`:
- `prefix` (String, default `"substrate:journal:"`)
- `maxAge` (Duration, default 24h)
- `maxLengthBytes` (long, default 1GB)

**RabbitMqJournalAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(Environment.class)` (com.rabbitmq.stream.Environment)
- Creates `RabbitMqJournal` bean from `Environment` and properties

### substrate-notifier-rabbitmq

Package: `org.jwcarman.substrate.notifier.rabbitmq`

**RabbitMqNotifier** — mirrors Odyssey's `RabbitMqOdysseyStreamNotifier`:
- Uses Spring AMQP (`spring-boot-starter-amqp`)
- Fanout exchange (durable) — broadcasts to all bound queues
- Each instance creates an exclusive auto-delete queue bound to the exchange
- `notify`: `RabbitTemplate.convertAndSend(exchange, "", key + "|" + payload)`
- `subscribe`: `SimpleMessageListenerContainer` with `MessageListener`, parse message,
  invoke handlers
- `RabbitAdmin` for declarative exchange/queue/binding setup

**RabbitMqNotifierProperties** — `@ConfigurationProperties(prefix = "substrate.notifier.rabbitmq")`:
- `exchangeName` (String, default `"substrate-notify"`)

**RabbitMqNotifierAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(ConnectionFactory.class)` (org.springframework.amqp)
- Creates `RabbitMqNotifier` bean

## Acceptance criteria

- [ ] Both modules compile and produce separate jars
- [ ] Each module has its own `AutoConfiguration.imports` registration
- [ ] Each auto-config suppresses the in-memory fallback for its SPI
- [ ] Properties load defaults from `*-defaults.properties` files
- [ ] Unit tests with mocked clients verify publish/consume operations
- [ ] Integration tests with Testcontainers RabbitMQ (with streams plugin enabled)
  verify full lifecycle:
  - Journal: append, readAfter (offset-based), readLast, complete, delete
  - Notifier: fanout delivery, multiple listeners, exclusive queue cleanup
- [ ] Producer pool caches producers per stream key (Journal)
- [ ] Auto-configuration tests verify bean creation
- [ ] All keys use configured prefix
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Modules added to `substrate-bom` and parent POM

## Implementation notes

- Reference Odyssey's RabbitMQ modules:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-eventlog-rabbitmq/`
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-notifier-rabbitmq/`
- RabbitMQ Streams (not classic queues) are the right choice for Journal — they
  support replay, offset tracking, and ordered consumption.
- The Notifier uses classic AMQP (fanout exchange), not RabbitMQ Streams.
- Testcontainers: use `rabbitmq:4-management` image with streams plugin enabled.
