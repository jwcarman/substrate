# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Breaking changes — Backend module consolidation

The 20 per-primitive backend modules have been consolidated into 9 per-backend modules.
Update your POM dependencies as follows:

| Removed artifact | Replaced by |
|---|---|
| `substrate-journal-redis` | `substrate-redis` |
| `substrate-mailbox-redis` | `substrate-redis` |
| `substrate-notifier-redis` | `substrate-redis` |
| `substrate-journal-hazelcast` | `substrate-hazelcast` |
| `substrate-mailbox-hazelcast` | `substrate-hazelcast` |
| `substrate-notifier-hazelcast` | `substrate-hazelcast` |
| `substrate-journal-postgresql` | `substrate-postgresql` |
| `substrate-mailbox-postgresql` | `substrate-postgresql` |
| `substrate-notifier-postgresql` | `substrate-postgresql` |
| `substrate-journal-nats` | `substrate-nats` |
| `substrate-mailbox-nats` | `substrate-nats` |
| `substrate-notifier-nats` | `substrate-nats` |
| `substrate-journal-mongodb` | `substrate-mongodb` |
| `substrate-mailbox-mongodb` | `substrate-mongodb` |
| `substrate-journal-dynamodb` | `substrate-dynamodb` |
| `substrate-mailbox-dynamodb` | `substrate-dynamodb` |
| `substrate-journal-rabbitmq` | `substrate-rabbitmq` |
| `substrate-notifier-rabbitmq` | `substrate-rabbitmq` |
| `substrate-journal-cassandra` | `substrate-cassandra` |
| `substrate-notifier-sns` | `substrate-sns` |

**Package changes:** Backend classes moved from `org.jwcarman.substrate.{primitive}.{backend}`
to `org.jwcarman.substrate.{backend}.{primitive}`.

**Configuration property changes:** Properties moved from `substrate.{primitive}.{backend}.*`
to `substrate.{backend}.{primitive}.*`.

**Per-primitive enable/disable:** Each primitive can now be disabled via
`substrate.{backend}.{primitive}.enabled=false` (all are enabled by default).

## [0.1.0] - 2026-04-07

### Added

- **Core SPIs** (`substrate-core`)
  - `JournalSpi` -- ordered, append-only, replayable stream (byte[]-based)
  - `MailboxSpi` -- single-value distributed storage (byte[]-based)
  - `Notifier` -- fire-and-forget cross-node signaling
  - `NotifierSubscription` -- cancellable notification handler registration
  - In-memory implementations for testing and single-node deployments
  - Spring Boot auto-configuration with `@ConditionalOnMissingBean` fallbacks

- **Typed Core API** via [Codec](https://github.com/jwcarman/codec) integration
  - `Journal<T>` -- typed, key-bound journal with `JournalCursor<T>` for blocking reads
  - `Mailbox<T>` -- typed, key-bound mailbox with `poll(Duration)` for blocking reads
  - `JournalFactory` / `MailboxFactory` -- create typed instances from `CodecFactory`
  - `JournalCursor<T>` -- poll-based cursor with Notifier-driven wake-ups and backpressure
  - Semaphore-based nudge model with dedicated reader virtual threads

- **Journal backends** (one module per backend)
  - `substrate-journal-redis` -- Redis Streams
  - `substrate-journal-postgresql` -- PostgreSQL with BYTEA storage
  - `substrate-journal-hazelcast` -- Hazelcast Ringbuffer
  - `substrate-journal-nats` -- NATS JetStream
  - `substrate-journal-dynamodb` -- AWS DynamoDB
  - `substrate-journal-mongodb` -- MongoDB collections with TTL indexes
  - `substrate-journal-cassandra` -- Apache Cassandra with TIMEUUID ordering
  - `substrate-journal-rabbitmq` -- RabbitMQ Streams

- **Mailbox backends** (one module per backend)
  - `substrate-mailbox-redis` -- Redis GET/SET with TTL
  - `substrate-mailbox-postgresql` -- PostgreSQL with BYTEA storage
  - `substrate-mailbox-hazelcast` -- Hazelcast IMap with TTL
  - `substrate-mailbox-nats` -- NATS KV Store
  - `substrate-mailbox-dynamodb` -- AWS DynamoDB
  - `substrate-mailbox-mongodb` -- MongoDB collections with TTL indexes

- **Notifier backends** (one module per backend)
  - `substrate-notifier-redis` -- Redis Pub/Sub
  - `substrate-notifier-postgresql` -- PostgreSQL LISTEN/NOTIFY
  - `substrate-notifier-hazelcast` -- Hazelcast ITopic
  - `substrate-notifier-nats` -- NATS Core pub/sub
  - `substrate-notifier-rabbitmq` -- RabbitMQ fanout exchange
  - `substrate-notifier-sns` -- AWS SNS/SQS fan-out

- **BOM** (`substrate-bom`) for version alignment across all modules

[0.1.0]: https://github.com/jwcarman/substrate/releases/tag/0.1.0
