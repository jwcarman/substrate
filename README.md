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

Distributed data structures for Spring Boot. Substrate provides four SPIs --
Atom, Journal, Mailbox, and Notifier -- that abstract away the underlying
infrastructure, letting applications work with distributed leased values,
streams, futures, and notifications without coupling to any specific technology.

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

You can mix backends — e.g., add `substrate-redis` for Atom + Mailbox +
Notifier and `substrate-postgresql` for Journal in the same application.

## Usage

All four primitives share a unified subscription model. A subscription's
`next(Duration)` returns a `NextResult<T>` sealed type that exhaustively
describes every possible outcome — `Value`, `Timeout`, `Completed`, `Expired`,
`Deleted`, or `Errored` — so consumers can pattern-match instead of juggling
nulls and exceptions.

### Atom -- distributed AtomicReference with TTL leasing

```java
@Autowired AtomFactory atomFactory;

// Create a new atom with an initial value and lease duration
Atom<Session> session = atomFactory.create(
    "session:abc", Session.class, new Session("user-42"), Duration.ofHours(1));

// Update the value (resets the TTL)
session.set(new Session("user-42", "updated"), Duration.ofHours(1));

// Read the current value
Snapshot<Session> snap = session.get();

// Renew the lease without changing the value
session.touch(Duration.ofHours(1));

// Subscribe to changes via blocking poll
try (BlockingSubscription<Snapshot<Session>> sub = session.subscribe()) {
    while (true) {
        switch (sub.next(Duration.ofSeconds(30))) {
            case NextResult.Value<Snapshot<Session>>(var s) -> handleChange(s);
            case NextResult.Timeout<Snapshot<Session>> t -> sendKeepAlive();
            case NextResult.Expired<Snapshot<Session>> e -> { handleExpired(); return; }
            case NextResult.Deleted<Snapshot<Session>> d -> { handleDeleted(); return; }
            default -> { return; }
        }
    }
}

// ... or with a callback
session.subscribe(snap -> System.out.println("New value: " + snap.value()));

// Lazy connect to an existing atom (no backend I/O until first call)
Atom<Session> existing = atomFactory.connect("session:abc", Session.class);
```

### Journal -- ordered, append-only, replayable stream

```java
@Autowired JournalFactory journalFactory;

// Create a typed journal bound to a key
Journal<OrderEvent> orders = journalFactory.create("orders:123", OrderEvent.class);

// Append entries
orders.append(new OrderEvent("created", 42));
orders.append(new OrderEvent("shipped", 42));

// Subscribe to entries with a blocking subscription (perfect for virtual threads)
try (BlockingSubscription<JournalEntry<OrderEvent>> sub = orders.subscribe()) {
    while (true) {
        switch (sub.next(Duration.ofSeconds(30))) {
            case NextResult.Value<JournalEntry<OrderEvent>>(var entry) -> processEvent(entry.value());
            case NextResult.Timeout<JournalEntry<OrderEvent>> t -> sendKeepAlive();
            case NextResult.Completed<JournalEntry<OrderEvent>> c -> { return; }
            case NextResult.Expired<JournalEntry<OrderEvent>> e -> { return; }
            default -> { return; }
        }
    }
}

// Resume from a known entry (e.g., SSE reconnect with Last-Event-ID)
orders.subscribe(lastSeenEntry);

// Mark the journal as complete (no more appends; subscribers receive Completed)
orders.complete();
```

### Mailbox -- single-shot distributed delivery

```java
@Autowired MailboxFactory mailboxFactory;

// Create a typed mailbox with a TTL
Mailbox<ElicitationResponse> mailbox =
    mailboxFactory.create("elicit:abc", ElicitationResponse.class, Duration.ofMinutes(5));

// Deliver a value (from any node) -- a second deliver throws MailboxFullException
mailbox.deliver(new ElicitationResponse("user picked option A"));

// Wait for delivery (blocks until delivered, expired, or deleted)
try (BlockingSubscription<ElicitationResponse> sub = mailbox.subscribe()) {
    switch (sub.next(Duration.ofMinutes(5))) {
        case NextResult.Value<ElicitationResponse>(var response) -> processResponse(response);
        case NextResult.Expired<ElicitationResponse> e -> handleExpired();
        case NextResult.Deleted<ElicitationResponse> d -> handleDeleted();
        default -> handleTimeout();
    }
}
```

### Notifier -- fire-and-forget signal

The Notifier is used internally by Atom, Journal, and Mailbox to wake up
subscribers when new data arrives. You don't typically use it directly, but
it's an SPI you can swap independently of the storage backend.

## Architecture

```
Consumer Code
     |
     v
Atom<T> / Journal<T> / Mailbox<T>      <-- Typed, key-bound, blocking API
     |        |        |
   Codec   Notifier  Subscription      <-- Serialization, signaling, NextResult
     |        |        |
     v        v        v
AtomSpi / JournalSpi / MailboxSpi      <-- Pure storage (byte[]-based)
     |
     v
 Backend Module                         <-- Redis, PostgreSQL, NATS, etc.
```

- **SPIs** are pure storage — read/write bytes, no threading, no notifications.
- **Core** handles orchestration — Notifier wake-ups, subscriptions,
  serialization via Codec, TTL sweeping.
- **Backends** are independently deployable — mix and match per SPI.

## Available Backends

Each backend module provides whichever SPIs that backend supports. One module
per backend, not per SPI.

| Backend    | Module | Atom | Journal | Mailbox | Notifier |
|------------|--------|:---:|:---:|:---:|:---:|
| In-Memory  | `substrate-core` (built-in fallback) | ✓ | ✓ | ✓ | ✓ |
| Redis      | `substrate-redis` | ✓ | ✓ | ✓ | ✓ |
| PostgreSQL | `substrate-postgresql` | ✓ | ✓ | ✓ | ✓ |
| Hazelcast  | `substrate-hazelcast` | ✓ | ✓ | ✓ | ✓ |
| NATS       | `substrate-nats` | ✓ | ✓ | ✓ | ✓ |
| MongoDB    | `substrate-mongodb` | ✓ | ✓ | ✓ | — |
| DynamoDB   | `substrate-dynamodb` | ✓ | ✓ | ✓ | — |
| Cassandra  | `substrate-cassandra` | ✓ | ✓ | — | — |
| RabbitMQ   | `substrate-rabbitmq` | — | ✓ | — | ✓ |
| SNS/SQS    | `substrate-sns` | — | — | — | ✓ |

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
    notifier:
      enabled: true
      channel-prefix: "substrate:notify:"
```

## Design Principles

- **Zero reactive types** — blocking APIs designed for virtual threads
- **Intentionally leased** — every primitive has an explicit TTL; nothing
  lives forever without renewal
- **SPIs are pure storage** — no notifications, no threading, no futures at
  the SPI layer
- **Unified subscription model** — all primitives share `BlockingSubscription`,
  `CallbackSubscription`, and `NextResult<T>` so consumers learn one pattern
- **Mix and match** — use Redis for Atom, PostgreSQL for Journal, NATS for
  Notifier in the same application
- **Spring Boot auto-configuration** — drop a backend module on the classpath
  and it registers; in-memory fallbacks for unconfigured primitives

## License

[Apache License 2.0](LICENSE)
