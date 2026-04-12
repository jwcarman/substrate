# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While substrate is in 0.x, the API is considered beta and breaking changes may
occur between minor versions. The 1.0.0 release will mark API stability.

## [Unreleased]

## [0.3.0] - 2026-04-11

### Breaking changes

#### Subscriber-based callback API

The callback subscribe API has been rewritten around `Subscriber<T>` and
`SubscriberConfig<T>`, replacing the previous `Consumer<T> onNext` +
`Consumer<CallbackSubscriberBuilder<T>> customizer` pattern.

**Removed types:**
- `CallbackSubscription` (marker interface) — callback subscribe methods
  now return plain `Subscription`
- `CallbackSubscriberBuilder<T>` — replaced by `SubscriberConfig<T>`

**Removed internal types:**
- `DefaultCallbackSubscriberBuilder<T>`
- `LifecycleCallbacks<T>`

**Changed subscribe signatures on Atom, Journal, and Mailbox:**

```java
// Before
CallbackSubscription subscribe(Consumer<T> onNext);
CallbackSubscription subscribe(Consumer<T> onNext, Consumer<CallbackSubscriberBuilder<T>> customizer);

// After
Subscription subscribe(Subscriber<T> subscriber);
Subscription subscribe(Consumer<SubscriberConfig<T>> customizer);
```

Migrate by either passing a `Subscriber<T>` lambda directly or using
the `SubscriberConfig<T>` customizer:

```java
// Lambda (simplest case — Subscriber is a @FunctionalInterface)
atom.subscribe((Snapshot<Session> snap) -> process(snap));

// SubscriberConfig customizer (for lifecycle handlers)
atom.subscribe(cfg -> cfg
    .onNext(snap -> process(snap))
    .onExpired(() -> reconnect())
    .onError(err -> log.error("boom", err)));
```

#### `NotifierSpi` is now byte-oriented (internal SPI)

The notifier backend SPI collapsed from `(String key, String payload)` to
opaque `byte[]` broadcast. This is an internal SPI change that only affects
custom `NotifierSpi` implementations — the public `Atom`/`Journal`/`Mailbox`
subscribe API is unchanged.

```java
// Before
public interface NotifierSpi {
  void notify(String key, String payload);
  NotifierSubscription subscribe(NotificationHandler handler);
}

// After
public interface NotifierSpi {
  void notify(byte[] payload);
  NotifierSubscription subscribe(Consumer<byte[]> handler);
}
```

Backends become dumb opaque-bytes transports: one channel/topic/subject
per node, no magic string sentinels, no type awareness. All the routing
and type discrimination moved up into the new `DefaultNotifier` layer in
`substrate-core`.

Two backend-config renames came with this: `substrate.redis.channelPrefix`
→ `substrate.redis.channel`, and `substrate.nats.subjectPrefix` →
`substrate.nats.subject`. Both now hold a single channel/subject name
instead of a prefix for fan-out.

### Added

#### Graceful shutdown coordinator

New `ShutdownCoordinator` bean registered as a Spring `SmartLifecycle` at
phase `Integer.MAX_VALUE`. It runs **before** the web server's graceful
shutdown phase and cancels every active substrate subscription, releasing
the feeder threads that back them. This fixes a 30-second hang at
`ApplicationContext` close when an SSE-style application holds long-lived
substrate subscriptions — Tomcat's graceful shutdown was waiting for
in-flight async requests whose `SseEmitter`s were parked inside
`BlockingSubscription.next(...)`.

Subscriptions auto-register on construction and unregister on cancel or
terminal state. Late registration after `stop()` cancels immediately.
Fan-out cancellation runs one virtual thread per subscription joined with
a bounded 5-second deadline.

#### New `NextResult.Cancelled<T>` variant

A new sealed variant on `NextResult<T>` distinct from `Completed` /
`Expired` / `Deleted`. Fires when the **local subscription** was torn
down via `cancel()` or the shutdown coordinator — the underlying
primitive is unaffected and other subscribers on other nodes keep
working. Clients that observe `Cancelled` should typically treat it as
"reconnect and resume" rather than "the data source is done."

This is a **breaking change** for exhaustive switch expressions over
`NextResult<T>` — add a `case NextResult.Cancelled<T> ignored -> ...`
branch.

#### Typed notifier routing layer (`DefaultNotifier`)

A new typed `Notifier` interface and `DefaultNotifier` implementation in
`substrate-core` replace direct `NotifierSpi` usage everywhere inside
core. Notifications are now delivered as a sealed `Notification`
hierarchy (`Changed` / `Completed` / `Deleted`), and `DefaultNotifier`
owns a per-`(PrimitiveType, key)` routing map that only delivers events
to the handlers that asked for them. No more client-side key filtering
in `FeederSupport`, no more magic `"__DELETED__"` / `"__COMPLETED__"`
sentinel strings on the wire.

The `Notifier` interface has seven typed producer methods and three
typed consumer methods:

```java
void notifyAtomChanged(String key);
void notifyAtomDeleted(String key);
void notifyJournalChanged(String key);
void notifyJournalCompleted(String key);
void notifyJournalDeleted(String key);
void notifyMailboxChanged(String key);
void notifyMailboxDeleted(String key);

NotifierSubscription subscribeToAtom(String key, Consumer<Notification> handler);
NotifierSubscription subscribeToJournal(String key, Consumer<Notification> handler);
NotifierSubscription subscribeToMailbox(String key, Consumer<Notification> handler);
```

`PrimitiveType` and `EventType` enums are package-private to the notifier
package — they're serialization implementation details that never appear
in public signatures.

### Changed

- **`DefaultCallbackSubscription` removed, replaced by
  `CallbackPumpSubscription`.** The push-style subscriber is now a thin
  wrapper around a `BlockingSubscription`: a dedicated virtual thread
  pumps values from the blocking source and dispatches each `NextResult`
  to the appropriate `Subscriber<T>` method. The pump loop is a
  `static` method that takes `(source, subscriber)` as locals, which
  means the `Thread.ofVirtual().start(...)` lambda captures locals
  instead of `this` — no `this`-escape from the constructor.
- **`DefaultJournal` / `DefaultJournalFactory` now take a `JournalLimits`
  record** bundling `(subscriptionQueueCapacity, maxInactivityTtl,
  maxEntryTtl, maxRetentionTtl)`. Dropped both constructors from 8
  parameters to 5.
- **`FeederSupport.start(...)` takes a `BiFunction<String,
  Consumer<Notification>, NotifierSubscription>` subscribe binding**
  instead of a `NotifierSpi` + type. Each primitive passes its own
  `notifier::subscribeToAtom` / `subscribeToJournal` / `subscribeToMailbox`
  method reference. The feeder never needs to mention `PrimitiveType`.

### Fixed

- **Spring context shutdown no longer hangs** when the application holds
  active substrate subscriptions (see ShutdownCoordinator above).
- **`NotifierSpi.markCancelled()` idempotency** fix in
  `BlockingBoundedHandoff` — the second call no longer tries to add a
  second `Cancelled` marker to the handoff queue.
- **`BlockingSubscription` default `close()`** now has test coverage
  demonstrating the `try-with-resources` → `cancel()` delegation in
  `substrate-api`.

### Internal

- Removed seven test-only "convenience constructors" from `DefaultAtom`,
  `DefaultJournal`, `DefaultMailbox`, their factories, and
  `DefaultBlockingSubscription`. They existed only to let tests skip
  passing a `ShutdownCoordinator`. Tests now declare a `private final
  ShutdownCoordinator coordinator = new ShutdownCoordinator()` field
  and pass it explicitly.
- Full Sonar pass: 1 bug and 29 code smells cleared, three new-code
  coverage gaps closed. Quality gate back to green.
- Dead `substrate.journal.max-ttl=7d` key removed from
  `substrate-defaults.properties` (the schema split into
  `max-inactivity-ttl` / `max-entry-ttl` / `max-retention-ttl` long ago;
  the defaults file never caught up).

## [0.2.1] - 2026-04-11

### Added

- **`BlockingSubscription` now extends `AutoCloseable`** with a default
  `close()` that delegates to `cancel()`. This makes try-with-resources
  the natural cancellation pattern for blocking subscriptions:

  ```java
  try (BlockingSubscription<Snapshot<Session>> sub = atom.subscribe()) {
      while (sub.isActive()) {
          // ... pull values via sub.next(timeout)
      }
  }
  ```

  No source-level breakage for existing callers — `cancel()` still works
  the same way, and the new `close()` is a default method.

### Documentation

- Substantial README rewrite to fix incorrect API examples that slipped
  into 0.2.0. The Atom, Journal, and Mailbox sections now use verified
  signatures: `Journal.append(data, ttl)`, `Journal.complete(retentionTtl)`,
  `Journal.subscribeAfter(id)` instead of a non-existent
  `subscribe(lastSeen)`, `JournalEntry.data()` instead of `.value()`,
  `JournalFactory.create(name, type, inactivityTtl)`, and the actual
  callback subscribe overloads with the `CallbackSubscriberBuilder`
  customizer for lifecycle handlers.

## [0.2.0] - 2026-04-11

### Breaking changes

#### Backend module consolidation

The 20 per-primitive backend modules from 0.1.0 have been consolidated into 9
per-backend modules. Each backend now ships a single module containing all SPI
implementations that backend supports. Update your dependencies as follows:

| Removed artifact | Replaced by |
|---|---|
| `substrate-journal-redis` | `substrate-redis` |
| `substrate-mailbox-redis` | `substrate-redis` |
| `substrate-notifier-redis` | `substrate-redis` |
| `substrate-journal-postgresql` | `substrate-postgresql` |
| `substrate-mailbox-postgresql` | `substrate-postgresql` |
| `substrate-notifier-postgresql` | `substrate-postgresql` |
| `substrate-journal-hazelcast` | `substrate-hazelcast` |
| `substrate-mailbox-hazelcast` | `substrate-hazelcast` |
| `substrate-notifier-hazelcast` | `substrate-hazelcast` |
| `substrate-journal-nats` | `substrate-nats` |
| `substrate-mailbox-nats` | `substrate-nats` |
| `substrate-notifier-nats` | `substrate-nats` |
| `substrate-journal-mongodb` | `substrate-mongodb` |
| `substrate-mailbox-mongodb` | `substrate-mongodb` |
| `substrate-journal-dynamodb` | `substrate-dynamodb` |
| `substrate-mailbox-dynamodb` | `substrate-dynamodb` |
| `substrate-journal-cassandra` | `substrate-cassandra` |
| `substrate-journal-rabbitmq` | `substrate-rabbitmq` |
| `substrate-notifier-rabbitmq` | `substrate-rabbitmq` |
| `substrate-notifier-sns` | `substrate-sns` |

#### Package moves

Backend classes moved from `org.jwcarman.substrate.{primitive}.{backend}` to
`org.jwcarman.substrate.{backend}.{primitive}`. For example,
`org.jwcarman.substrate.journal.redis.RedisJournalSpi` is now
`org.jwcarman.substrate.redis.journal.RedisJournalSpi`.

#### Configuration property path changes

Properties moved from `substrate.{primitive}.{backend}.*` to
`substrate.{backend}.{primitive}.*`. For example:

| Old (0.1.0) | New (0.2.0) |
|---|---|
| `substrate.journal.redis.prefix` | `substrate.redis.journal.prefix` |
| `substrate.mailbox.redis.default-ttl` | `substrate.redis.mailbox.default-ttl` |

#### Per-primitive enable/disable

Each primitive can now be disabled individually via
`substrate.{backend}.{primitive}.enabled=false` (all enabled by default).

### Notifier reframed as an internal SPI

`NotifierSpi` is no longer a user-facing primitive. It exists as a pluggable
cross-node wake-up transport that the three primitives (Atom, Journal,
Mailbox) use internally to notify subscribers when state changes. Each
backend module supplies its own `NotifierSpi` implementation
automatically; you don't need to interact with it directly.

### Added

#### Atom — distributed AtomicReference

A new primitive for distributed leased values:

- **`AtomSpi`** with `create`, `read`, `set`, `touch`, `delete`, and lazy
  expiry on read.
- **`Atom<T>`** typed wrapper with subscription support for change
  notifications.
- **`AtomFactory`** with both eager `create(...)` and lazy `connect(...)`
  modes.
- TTL-based leasing with SHA-256 staleness tokens to detect concurrent
  modifications.
- **Atom backend support** for all 7 storage backends:
  Cassandra, DynamoDB, Hazelcast, MongoDB, NATS, PostgreSQL, Redis.

#### Journal lifecycle state machine

Journals now have an explicit lifecycle: **active → completed → expired**.

- **`Journal.complete()`** marks a stream as immutable; further `append`
  calls throw `JournalCompletedException`.
- **`JournalCompletedException`** signals an attempt to write to a completed
  journal.
- **Inactivity TTL + retention TTL**: a completed journal stays readable until
  its retention TTL expires.
- **`isComplete(key)`** SPI method for backends and consumers to detect the
  terminal state.

#### Mailbox single-shot semantics

- **`MailboxFullException`** is thrown if a second `deliver` is attempted on
  the same mailbox; the first delivery is preserved.
- **`MailboxExpiredException`** is thrown if a read or deliver targets an
  expired mailbox.
- Mailboxes are explicitly created via `MailboxFactory.create(name, ttl)`
  before delivery, separating creation from delivery semantics.

#### Unified subscription model

All three primitives (Atom, Journal, Mailbox) share the same subscription
abstractions:

- **`BlockingSubscription<T>`** — `next(Duration)` returns a `NextResult<T>`
  sealed interface (`Value`, `Timeout`, `Completed`, `Expired`, `Deleted`,
  `Errored`) that exhaustively models all subscription outcomes.
- **`CallbackSubscription`** — fluent `CallbackSubscriberBuilder` for
  `onError`, `onExpiration`, `onDelete`, `onComplete` handlers.
- **Three internal handoff strategies** matched to each primitive's needs:
  coalescing latest-wins (Atom), bounded blocking FIFO (Journal), and
  single-shot sealed (Mailbox).

#### Sweeper

- **`Sweeper`** — single per-primitive background virtual thread that calls
  `Sweepable.sweep(int)` on a jittered schedule to evict expired entries.
  Replaces the previous per-SPI cleanup approaches.
- **`Sweepable`** — opt-in interface for SPIs that need active expiry.

#### Operability

- Feeder thread lifecycle logging at `DEBUG` (start/exit) and `WARN`
  (unexpected errors), routed through `org.apache.commons.logging`.
- Comprehensive Javadoc on all public API types in `substrate-api`.
- Per-primitive Spring Boot conditional beans
  (`substrate.<backend>.<primitive>.enabled`).

### Changed

- `RawAtom`, `RawJournalEntry`, and other records with `byte[]` fields now
  override `equals`/`hashCode`/`toString` to use content-based semantics for
  the array components, fixing reference-equality surprises.
- In-memory SPIs share an internal `ExpiringEntry<V>` helper for TTL
  bookkeeping.
- `NextHandoff` implementations share an `AbstractHandoff` /
  `AbstractSingleSlotHandoff` hierarchy that captures the common terminal-mark
  dispatch and pull-loop scaffolding.

### Fixed

- The in-memory fallback warning messages now reference real backend module
  names (`substrate-redis`) instead of the per-primitive names from 0.1.0
  that no longer exist.
- `DefaultCallbackSubscription.cancel()` now actually runs the canceller
  closure, eliminating a feeder-thread + notifier-subscription leak on
  cancellation.
- `BlockingSubscription` no longer spins after thread interrupt — `done` is
  flipped on the interrupt path so `isActive()` returns false.

### Requirements

- Java 25+
- Spring Boot 4.x
- [Codec](https://github.com/jwcarman/codec) 0.1.0+ for the typed API

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

[0.3.0]: https://github.com/jwcarman/substrate/releases/tag/0.3.0
[0.2.1]: https://github.com/jwcarman/substrate/releases/tag/0.2.1
[0.2.0]: https://github.com/jwcarman/substrate/releases/tag/0.2.0
[0.1.0]: https://github.com/jwcarman/substrate/releases/tag/0.1.0
