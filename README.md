# Substrate

[![Maven Central](https://img.shields.io/maven-central/v/org.jwcarman.substrate/substrate-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/org.jwcarman.substrate/substrate-core)
[![CI](https://github.com/jwcarman/substrate/actions/workflows/maven.yml/badge.svg)](https://github.com/jwcarman/substrate/actions/workflows/maven.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https://raw.githubusercontent.com/jwcarman/substrate/main/pom.xml&query=//*[local-name()='java.version']/text()&label=Java&color=orange)](https://openjdk.org/)

[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)

Distributed data structures for Spring Boot. Substrate provides three SPIs --
Journal, Mailbox, and Notifier -- that abstract away the underlying infrastructure,
letting applications work with distributed streams, futures, and notifications
without coupling to any specific technology.

## Requirements

- Java 25+
- Spring Boot 4.x
- [Codec](https://github.com/jwcarman/codec) 0.1.0+ (for typed API)

## Quick Start

Add the BOM and the modules you need:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.jwcarman.substrate</groupId>
            <artifactId>substrate-bom</artifactId>
            <version>0.1.0</version>
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

    <!-- Pick backends for each SPI you need -->
    <dependency>
        <groupId>org.jwcarman.substrate</groupId>
        <artifactId>substrate-journal-redis</artifactId>
    </dependency>
    <dependency>
        <groupId>org.jwcarman.substrate</groupId>
        <artifactId>substrate-notifier-redis</artifactId>
    </dependency>
</dependencies>
```

## Usage

### Journal -- ordered, append-only, replayable stream

```java
@Autowired JournalFactory journalFactory;

// Create a typed journal bound to a key
Journal<OrderEvent> orders = journalFactory.create("orders:123", OrderEvent.class);

// Append entries
orders.append(new OrderEvent("created", 42));
orders.append(new OrderEvent("shipped", 42));

// Read with a blocking cursor (perfect for virtual threads)
try (JournalCursor<OrderEvent> cursor = orders.read()) {
    while (cursor.isOpen()) {
        Optional<JournalEntry<OrderEvent>> entry = cursor.poll(Duration.ofSeconds(30));
        if (entry.isPresent()) {
            processEvent(entry.get().data());
        } else {
            sendKeepAlive(); // timeout -- no new entries
        }
    }
}

// Resume from a cursor position (e.g., SSE reconnect with Last-Event-ID)
try (JournalCursor<OrderEvent> cursor = orders.readAfter(lastEventId)) {
    // picks up where it left off
}

// Replay the last N entries, then continue live
try (JournalCursor<OrderEvent> cursor = orders.readLast(50)) {
    // delivers last 50 entries first, then continues live
}

// Mark the journal as complete (no more appends)
orders.complete();
```

### Mailbox -- single-value distributed future

```java
@Autowired MailboxFactory mailboxFactory;

// Create a typed mailbox bound to a key
Mailbox<ElicitationResponse> mailbox = mailboxFactory.create("elicit:abc", ElicitationResponse.class);

// Deliver a value (from any node)
mailbox.deliver(new ElicitationResponse("user picked option A"));

// Poll for the value (blocks until delivered or timeout)
Optional<ElicitationResponse> response = mailbox.poll(Duration.ofMinutes(5));
if (response.isPresent()) {
    processResponse(response.get());
} else {
    handleTimeout();
}
```

### Notifier -- fire-and-forget signal

The Notifier is used internally by Journal and Mailbox to wake up readers when new
data arrives. You don't typically use it directly.

## Architecture

```
Consumer Code
     |
     v
Journal<T> / Mailbox<T>    <-- Typed, key-bound, blocking API
     |           |
   Codec       Notifier     <-- Serialization + signaling
     |           |
     v           v
 JournalSpi / MailboxSpi    <-- Pure storage (byte[]-based)
     |
     v
 Backend Module              <-- Redis, PostgreSQL, NATS, etc.
```

- **SPIs** are pure storage -- read/write bytes, no threading, no notifications
- **Core** handles orchestration -- Notifier wake-ups, cursors, serialization via Codec
- **Backends** are independently deployable -- mix and match per SPI

## Available Backends

| Backend    | Journal | Mailbox | Notifier |
|------------|---------|---------|----------|
| In-Memory  | built-in | built-in | built-in |
| Redis      | `substrate-journal-redis` | `substrate-mailbox-redis` | `substrate-notifier-redis` |
| PostgreSQL | `substrate-journal-postgresql` | `substrate-mailbox-postgresql` | `substrate-notifier-postgresql` |
| Hazelcast  | `substrate-journal-hazelcast` | `substrate-mailbox-hazelcast` | `substrate-notifier-hazelcast` |
| NATS       | `substrate-journal-nats` | `substrate-mailbox-nats` | `substrate-notifier-nats` |
| DynamoDB   | `substrate-journal-dynamodb` | `substrate-mailbox-dynamodb` | -- |
| MongoDB    | `substrate-journal-mongodb` | `substrate-mailbox-mongodb` | -- |
| Cassandra  | `substrate-journal-cassandra` | -- | -- |
| RabbitMQ   | `substrate-journal-rabbitmq` | -- | `substrate-notifier-rabbitmq` |
| SNS/SQS    | -- | -- | `substrate-notifier-sns` |

## Configuration

Each backend has its own configuration properties with sensible defaults:

```yaml
substrate:
  journal:
    redis:
      prefix: "substrate:journal:"
      max-len: 100000
      default-ttl: 1h
  mailbox:
    redis:
      prefix: "substrate:mailbox:"
      default-ttl: 5m
  notifier:
    redis:
      channel-prefix: "substrate:notify:"
```

## Design Principles

- **Zero reactive types** -- blocking APIs designed for virtual threads
- **SPIs are pure storage** -- no Notifier, no threading, no futures
- **Core handles orchestration** -- Notifier-driven cursors with semaphore nudge model
- **Mix and match** -- use Redis for Journal, NATS for Notifier, Hazelcast for Mailbox
- **Spring Boot auto-configuration** -- drop a module on the classpath, it registers

## License

[Apache License 2.0](LICENSE)
