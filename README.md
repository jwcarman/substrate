# Substrate

[![CI](https://github.com/jwcarman/substrate/actions/workflows/maven.yml/badge.svg)](https://github.com/jwcarman/substrate/actions/workflows/maven.yml)
[![CodeQL](https://github.com/jwcarman/substrate/actions/workflows/github-code-scanning/codeql/badge.svg)](https://github.com/jwcarman/substrate/actions/workflows/github-code-scanning/codeql)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https://raw.githubusercontent.com/jwcarman/substrate/main/pom.xml&query=//*[local-name()='java.version']/text()&label=Java&color=orange)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/org.jwcarman.substrate/substrate-core)](https://central.sonatype.com/artifact/org.jwcarman.substrate/substrate-core)

[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)

Distributed data structures for Spring Boot. Substrate provides three
primitives -- Atom, Journal, and Mailbox -- that abstract away the underlying
infrastructure, letting applications work with distributed leased values,
streams, and futures without coupling to any specific technology.

## Requirements

- Java 25+
- Spring Boot 4.x
- [Codec](https://github.com/jwcarman/codec) 0.1.0+ (for typed API)

## Quick Start

Add the BOM and the backend modules you need. Backend modules are organized
**per backend** -- one module per technology, supplying whichever SPI
implementations that backend supports.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.jwcarman.substrate</groupId>
            <artifactId>substrate-bom</artifactId>
            <version>0.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Core (required) -->
    <dependency>
        <groupId>org.jwcarman.substrate</groupId>
        <artifactId>substrate-core</artifactId>
    </dependency>

    <!-- Pick a codec backend for typed API -->
    <dependency>
        <groupId>org.jwcarman.codec</groupId>
        <artifactId>codec-jackson</artifactId>
        <version>0.1.0</version>
    </dependency>

    <!-- Pick a backend module (one per backend, not one per SPI) -->
    <dependency>
        <groupId>org.jwcarman.substrate</groupId>
        <artifactId>substrate-redis</artifactId>
    </dependency>
</dependencies>
```

You can mix backends — e.g., add `substrate-redis` for Atom and Mailbox and
`substrate-postgresql` for Journal in the same application.

**Each backend module is a Spring Boot auto-configuration.** Drop it on the
classpath and the SPIs it provides register themselves. There's no
`@EnableSubstrate`, no manual `@Bean` wiring, no `@ComponentScan`. If a
primitive has no backend on the classpath, an in-memory implementation is
registered as a fallback (with a one-time warning), so you can develop and
test against single-node defaults and slot in a real backend later.

## Usage

All three primitives share a unified subscription model. A subscription's
`next(Duration)` returns a `NextResult<T>` sealed type that exhaustively
describes every possible outcome — `Value`, `Timeout`, `Completed`, `Expired`,
`Deleted`, or `Errored` — so consumers can pattern-match instead of juggling
nulls and exceptions.

### Atom -- distributed AtomicReference with TTL leasing

```java
@Autowired AtomFactory atomFactory;

// Create a new atom with an initial value and lease duration. The lease
// duration is the TTL — the atom expires unless touch()/set() renews it.
Atom<Session> session = atomFactory.create(
    "session:abc", Session.class, new Session("user-42"), Duration.ofHours(1));

// Update the value (resets the TTL)
session.set(new Session("user-42", "updated"), Duration.ofHours(1));

// Synchronously read the current value. Returns a Snapshot<T> containing
// the current value plus a staleness token (a SHA-256 fingerprint the SPI
// uses to detect changes). This is the fast path for "what is the current
// value right now?" — no subscription, no polling.
Snapshot<Session> snap = session.get();
Session current = snap.value();

// Extend the lease without changing the value
session.touch(Duration.ofHours(1));

// Subscribe to changes via blocking poll (good for virtual threads).
// BlockingSubscription extends AutoCloseable, so try-with-resources cancels
// the subscription on scope exit.
try (BlockingSubscription<Snapshot<Session>> sub = session.subscribe()) {
    while (sub.isActive()) {
        switch (sub.next(Duration.ofSeconds(30))) {
            case NextResult.Value<Snapshot<Session>>(var s) -> handleChange(s);
            case NextResult.Timeout<Snapshot<Session>> ignored -> sendKeepAlive();
            case NextResult.Expired<Snapshot<Session>> ignored -> log.info("lease expired");
            case NextResult.Deleted<Snapshot<Session>> ignored -> log.info("atom deleted");
            case NextResult.Errored<Snapshot<Session>>(var cause) -> log.error("error", cause);
            case NextResult.Completed<Snapshot<Session>> ignored -> {}
        }
    }
}

// ... or push values to a callback handler
CallbackSubscription sub = session.subscribe(this::handleChange);

// ... or push values plus register lifecycle handlers via the customizer
CallbackSubscription sub = session.subscribe(
    this::handleChange,
    builder -> builder
        .onError(err -> log.error("subscription failed", err))
        .onExpiration(() -> log.info("session lease expired"))
        .onDelete(() -> log.info("session deleted by another node")));

// Cancel the callback subscription when you're done
sub.cancel();

// Resume from a known snapshot — only deliver values whose token differs
// from lastSeen. Useful for reconnect-after-blip or for skipping the
// initial state when the caller already has it locally.
Snapshot<Session> lastSeen = session.get();
processInitialState(lastSeen);
try (BlockingSubscription<Snapshot<Session>> resumed = session.subscribe(lastSeen)) {
    // resumed only delivers Snapshots whose token differs from lastSeen
}

// Lazy connect to an existing atom (no backend I/O until the first operation)
Atom<Session> existing = atomFactory.connect("session:abc", Session.class);
```

### Journal -- ordered, append-only, replayable stream

```java
@Autowired JournalFactory journalFactory;

// Create a typed journal. The inactivity TTL is how long the journal lives
// without an append before it expires.
Journal<OrderEvent> orders = journalFactory.create(
    "orders:123", OrderEvent.class, Duration.ofDays(7));

// Append entries — each append takes its own per-entry TTL and resets the
// journal's inactivity timer. Returns the generated entry id.
String createdId = orders.append(new OrderEvent("created", 42), Duration.ofDays(30));
String shippedId = orders.append(new OrderEvent("shipped", 42), Duration.ofDays(30));

// Subscribe from the current tail. New entries are delivered as they arrive;
// historical entries already in the journal are NOT replayed.
try (BlockingSubscription<JournalEntry<OrderEvent>> sub = orders.subscribe()) {
    while (sub.isActive()) {
        switch (sub.next(Duration.ofSeconds(30))) {
            case NextResult.Value<JournalEntry<OrderEvent>>(var entry) -> processEvent(entry.data());
            case NextResult.Timeout<JournalEntry<OrderEvent>> ignored -> sendKeepAlive();
            case NextResult.Completed<JournalEntry<OrderEvent>> ignored -> log.info("journal completed");
            case NextResult.Expired<JournalEntry<OrderEvent>> ignored -> log.info("journal expired");
            case NextResult.Deleted<JournalEntry<OrderEvent>> ignored -> log.info("journal deleted");
            case NextResult.Errored<JournalEntry<OrderEvent>>(var cause) -> log.error("error", cause);
        }
    }
}

// Resume from a known checkpoint id (e.g., SSE reconnect with Last-Event-ID).
// Replays all entries strictly after afterId, then continues live.
try (var resumed = orders.subscribeAfter(lastSeenId)) {
    // ...
}

// Replay the last N retained entries, then continue live.
try (var tail = orders.subscribeLast(50)) {
    // ...
}

// Mark the journal as complete with a retention TTL. After complete(), no
// further appends are accepted; subscribers receive NextResult.Completed
// once the journal is fully drained, and the journal stays readable until
// the retention TTL elapses.
orders.complete(Duration.ofDays(7));
```

### Mailbox -- single-shot distributed delivery

```java
@Autowired MailboxFactory mailboxFactory;

// Create a typed mailbox with a TTL
Mailbox<ElicitationResponse> mailbox = mailboxFactory.create(
    "elicit:abc", ElicitationResponse.class, Duration.ofMinutes(5));

// Deliver a value (from any node) — a second deliver throws MailboxFullException
mailbox.deliver(new ElicitationResponse("user picked option A"));

// Wait for delivery via blocking subscribe. The first next() returns the
// delivered value if it's already there, otherwise blocks until delivery.
// After the value is consumed, subsequent next() calls return Completed.
try (BlockingSubscription<ElicitationResponse> sub = mailbox.subscribe()) {
    switch (sub.next(Duration.ofMinutes(5))) {
        case NextResult.Value<ElicitationResponse>(var response) -> processResponse(response);
        case NextResult.Timeout<ElicitationResponse> ignored -> handleTimeout();
        case NextResult.Expired<ElicitationResponse> ignored -> handleExpired();
        case NextResult.Deleted<ElicitationResponse> ignored -> handleDeleted();
        case NextResult.Completed<ElicitationResponse> ignored -> {}
        case NextResult.Errored<ElicitationResponse>(var cause) -> log.error("error", cause);
    }
}

// ... or via callback. The handler fires exactly once per subscription;
// onComplete fires after the handler returns.
mailbox.subscribe(
    this::processResponse,
    builder -> builder
        .onExpiration(() -> log.info("mailbox expired before delivery"))
        .onDelete(() -> log.info("mailbox deleted before delivery")));
```


## Architecture

```
Consumer Code
     |
     v
Atom<T> / Journal<T> / Mailbox<T>      <-- Typed, key-bound, blocking API
     |        |        |
     v        v        v
AtomSpi / JournalSpi / MailboxSpi      <-- Pure storage (byte[]-based)
     |
     v
 Backend Module                         <-- Redis, PostgreSQL, NATS, etc.
```

- **Primitives** are typed, key-bound, blocking APIs designed for virtual
  threads. They handle serialization (via Codec), subscription orchestration,
  and TTL management.
- **SPIs** are pure storage — read/write bytes, no threading, no callbacks.
- **Backends** are independently deployable. Internally, each backend module
  also supplies a `NotifierSpi` that the primitives use to wake subscribers
  across nodes; this is an implementation detail you don't need to use
  directly.

## Available Backends

Each backend module provides whichever primitives that backend supports. One
module per backend, not per primitive.

| Backend    | Module | Atom | Journal | Mailbox |
|------------|--------|:---:|:---:|:---:|
| In-Memory  | `substrate-core` (built-in fallback) | ✓ | ✓ | ✓ |
| Redis      | `substrate-redis` | ✓ | ✓ | ✓ |
| PostgreSQL | `substrate-postgresql` | ✓ | ✓ | ✓ |
| Hazelcast  | `substrate-hazelcast` | ✓ | ✓ | ✓ |
| NATS       | `substrate-nats` | ✓ | ✓ | ✓ |
| MongoDB    | `substrate-mongodb` | ✓ | ✓ | ✓ |
| DynamoDB   | `substrate-dynamodb` | ✓ | ✓ | ✓ |
| Cassandra  | `substrate-cassandra` | ✓ | ✓ | — |
| RabbitMQ   | `substrate-rabbitmq` | — | ✓ | — |
| SNS/SQS    | `substrate-sns` | — | — | — |

> **Note:** Most backend modules also supply a `NotifierSpi` implementation
> that the primitives use internally to wake subscribers across nodes. You
> can mix and match: e.g., use `substrate-postgresql` for storage and add
> `substrate-rabbitmq` or `substrate-sns` purely for the cross-node wake-up
> transport. `substrate-sns` is notification-only — it doesn't provide any
> of the three primitives, just an SNS/SQS-backed `NotifierSpi`.

## Configuration

Each backend has its own configuration properties under
`substrate.<backend>.<primitive>.*`. Sensible defaults are provided. Each
primitive can be enabled or disabled independently.

```yaml
substrate:
  redis:
    atom:
      enabled: true
      prefix: "substrate:atom:"
      default-ttl: 1h
    journal:
      enabled: true
      prefix: "substrate:journal:"
      max-len: 100000
      default-ttl: 1h
    mailbox:
      enabled: true
      prefix: "substrate:mailbox:"
      default-ttl: 5m
```

## Design Principles

- **Blocking-first, callback-optional** — the primary API is blocking
  (`BlockingSubscription.next(Duration)`) and designed for virtual threads.
  A `CallbackSubscription` flavor is also available when fire-and-forget
  callback semantics are a better fit. No `Mono`/`Flux` or other
  Reactive Streams dependencies.
- **Intentionally leased** — every primitive has an explicit TTL; nothing
  lives forever without renewal
- **SPIs are pure storage** — no notifications, no threading, no futures at
  the SPI layer
- **Unified subscription model** — all primitives share `BlockingSubscription`,
  `CallbackSubscription`, and `NextResult<T>` so consumers learn one pattern
- **Mix and match** — use Redis for Atom, PostgreSQL for Journal, and NATS
  for Mailbox in the same application
- **Spring Boot auto-configuration** — drop a backend module on the classpath
  and it registers; in-memory fallbacks for unconfigured primitives

## License

[Apache License 2.0](LICENSE)
