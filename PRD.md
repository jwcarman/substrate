# PRD — Substrate

---

## What this project is

Substrate is a distributed data structures library for Spring Boot. It provides three
SPIs — Journal, Mailbox, and Notifier — that abstract away the underlying infrastructure,
letting applications work with distributed streams, futures, and notifications without
coupling to any specific technology.

Substrate was born from two real consumers:

- **Odyssey** — clustered, resumable Server-Sent Events. Uses Journal (ordered event
  stream) + Notifier (wake-up signal) to deliver SSE events across a cluster.
- **Mocapi** — MCP server framework. Uses Mailbox (distributed future) + Notifier
  for elicitation (ask user a question, wait for the answer across nodes).

Both need the same infrastructure backends (Redis, Hazelcast, PostgreSQL, etc.) but
with different access patterns. Substrate is the shared foundation.

---

## Goals

- Three clean SPIs: Journal, Mailbox, Notifier
- Mix-and-match backends per SPI (e.g., Cassandra Journal + NATS Notifier)
- In-memory fallback for testing and single-node deployments
- Spring Boot auto-configuration — drop a backend on the classpath, it registers
- Zero reactive types — virtual threads throughout
- Published to Maven Central with BOM for version alignment

## Non-Goals

- Application-level semantics (SSE, MCP, etc.) — that's what consumers build
- Exactly-once delivery guarantees
- Transaction support across SPIs
- Multi-tenancy built in (consumers handle tenant isolation)

---

## Tech stack

- Language: Java 25
- Framework: Spring Boot 4.x
- Build tool: Maven (multi-module)
- Testing: JUnit 5 + Mockito + Testcontainers 2.x + Awaitility
- Linting / formatting: Spotless with Google Java Format
- License: Apache 2.0

---

## SPIs

### Journal — ordered, append-only, replayable stream

The n-ary case. Multiple items appended over time, cursor-based reads, explicit
completion by the producer.

```java
public interface Journal {
    String append(String key, String data);
    Stream<JournalEntry> readAfter(String key, String afterId);
    Stream<JournalEntry> readLast(String key, int count);
    void complete(String key);
    void delete(String key);
    
    String journalKey(String name);
}

public record JournalEntry(String id, String key, String data, Instant timestamp) {}
```

- `append`: adds an entry, returns the entry ID (backend-specific, time-ordered)
- `readAfter`: returns entries strictly after the given ID, lazily via `Stream`
- `readLast`: returns the last N entries in chronological order
- `complete`: marks the journal as done — no more appends
- `delete`: removes the journal and all entries
- `journalKey`: builds a key using the backend's naming conventions

**Use case in Odyssey:** each SSE stream is a Journal. Publishing an event = append.
Subscribing = readAfter. Replaying = readLast. Closing the stream = complete.

### Mailbox — single-value distributed future

The unary case. One value delivered, auto-completes on delivery.

```java
public interface Mailbox {
    void deliver(String key, String value);
    CompletableFuture<String> await(String key, Duration timeout);
    void delete(String key);
    
    String mailboxKey(String name);
}
```

- `deliver`: puts the value and marks the mailbox as complete
- `await`: waits for the value to arrive, returns it as a future
- `delete`: removes the mailbox
- `mailboxKey`: builds a key using the backend's naming conventions

**Use case in Mocapi:** MCP elicitation. Server creates a mailbox, sends a prompt
to the client, client responds, server's `await` future resolves with the answer.

### Notifier — fire-and-forget signal

Shared by both Journal and Mailbox consumers. "Something changed, go look."

```java
public interface Notifier {
    void notify(String key, String payload);
    void subscribe(NotificationHandler handler);
}

@FunctionalInterface
public interface NotificationHandler {
    void onNotification(String key, String payload);
}
```

- `notify`: broadcasts a signal to all nodes
- `subscribe`: registers a handler (one per node, called for all notifications)

---

## Module Structure

```
substrate/
├── substrate-bom/                          # BOM for version alignment
├── substrate-core/                         # SPIs, in-memory implementations, auto-config
│   ├── spi/
│   │   ├── Journal.java
│   │   ├── JournalEntry.java
│   │   ├── Mailbox.java
│   │   ├── Notifier.java
│   │   └── NotificationHandler.java
│   ├── memory/
│   │   ├── InMemoryJournal.java
│   │   ├── InMemoryMailbox.java
│   │   └── InMemoryNotifier.java
│   └── autoconfigure/
│       ├── SubstrateAutoConfiguration.java
│       └── SubstrateProperties.java
│
│   # Journal implementations (one module per backend)
├── substrate-journal-redis/                # Redis Streams
├── substrate-journal-postgresql/           # PostgreSQL table
├── substrate-journal-cassandra/            # Cassandra table (TIMEUUID)
├── substrate-journal-dynamodb/             # DynamoDB table
├── substrate-journal-mongodb/              # MongoDB collection
├── substrate-journal-rabbitmq/             # RabbitMQ Streams
├── substrate-journal-nats/                 # NATS JetStream
├── substrate-journal-hazelcast/            # Hazelcast Ringbuffer
│
│   # Mailbox implementations (one module per backend)
├── substrate-mailbox-redis/                # Redis GET/SET + TTL
├── substrate-mailbox-postgresql/           # PostgreSQL table
├── substrate-mailbox-hazelcast/            # Hazelcast IMap
├── substrate-mailbox-dynamodb/             # DynamoDB PutItem/GetItem
├── substrate-mailbox-nats/                 # NATS KV Store
├── substrate-mailbox-mongodb/              # MongoDB collection
│
│   # Notifier implementations (one module per backend)
├── substrate-notifier-redis/               # Redis Pub/Sub
├── substrate-notifier-postgresql/          # PostgreSQL LISTEN/NOTIFY
├── substrate-notifier-nats/                # NATS Core
├── substrate-notifier-sns/                 # AWS SNS + SQS fan-out
├── substrate-notifier-rabbitmq/            # RabbitMQ fanout exchange
├── substrate-notifier-hazelcast/           # Hazelcast ITopic
│
└── substrate-example/                      # Example app (not published)
```

Each module is independently deployable. Modules that share the same backend
(e.g., `substrate-journal-redis` and `substrate-notifier-redis`) are separate
jars — you can use one without the other.

**Dependency rules:**
- Journal requires a Notifier (the notifier nudges readers when new entries arrive)
- Mailbox requires a Notifier (the notifier wakes up the awaiting future)
- Journal and Mailbox are independent of each other
- Notifier can be deployed standalone (but has no consumer without Journal or Mailbox)

Minimum viable deployments:
- **Stream use case (Odyssey):** one Journal module + one Notifier module
- **Future use case (Mocapi):** one Mailbox module + one Notifier module
- **Both:** one Journal + one Mailbox + one Notifier (can be different backends)

| Backend | Journal | Mailbox | Notifier |
|---|---|---|---|
| In-Memory | built into core | built into core | built into core |
| Redis | `substrate-journal-redis` | `substrate-mailbox-redis` | `substrate-notifier-redis` |
| Hazelcast | `substrate-journal-hazelcast` | `substrate-mailbox-hazelcast` | `substrate-notifier-hazelcast` |
| PostgreSQL | `substrate-journal-postgresql` | `substrate-mailbox-postgresql` | `substrate-notifier-postgresql` |
| Cassandra | `substrate-journal-cassandra` | — | — (use external) |
| DynamoDB | `substrate-journal-dynamodb` | `substrate-mailbox-dynamodb` | — (use external) |
| NATS | `substrate-journal-nats` | `substrate-mailbox-nats` | `substrate-notifier-nats` |
| MongoDB | `substrate-journal-mongodb` | `substrate-mailbox-mongodb` | — |
| RabbitMQ | `substrate-journal-rabbitmq` | — | `substrate-notifier-rabbitmq` |
| SNS/SQS | — | — | `substrate-notifier-sns` |

### Auto-configuration

Each backend module self-registers via `@AutoConfiguration` with
`@ConditionalOnClass`. `substrate-core` provides in-memory fallbacks with
`@ConditionalOnMissingBean` and WARN logging.

Each SPI is independently wired — you can use Redis for Journal, NATS for
Notifier, and Hazelcast for Mailbox. The consumer just needs one bean of each
type they use.

---

## How Odyssey Uses Substrate

After extraction, Odyssey's dependency changes from its own SPIs to Substrate:

```java
// Before (Odyssey's own SPI)
OdysseyEventLog eventLog;
OdysseyStreamNotifier notifier;

// After (Substrate)
Journal journal;
Notifier notifier;
```

The engine (StreamSubscriber, StreamWriter, etc.) stays in Odyssey. Only the
storage and notification SPIs move to Substrate.

## How Mocapi Uses Substrate

```java
// Elicitation
Mailbox mailbox;
Notifier notifier;

// Create a mailbox for this elicitation
String key = mailbox.mailboxKey("elicit:" + requestId);

// Send the elicitation prompt to the client (via SSE/Odyssey)
stream.publishRaw(elicitationPrompt);

// Wait for the client's response
CompletableFuture<String> response = mailbox.await(key, Duration.ofMinutes(5));
String answer = response.join();
```

---

## Configuration

```yaml
substrate:
  journal:
    redis:
      ephemeral-prefix: "substrate:journal:ephemeral:"
      channel-prefix: "substrate:journal:channel:"
      broadcast-prefix: "substrate:journal:broadcast:"
      max-len: 100000
      ephemeral-ttl: 5m
      channel-ttl: 1h
      broadcast-ttl: 24h
  mailbox:
    redis:
      prefix: "substrate:mailbox:"
      default-ttl: 5m
  notifier:
    redis:
      channel-prefix: "substrate:notify:"
```

Each backend module has its own `@ConfigurationProperties` record with defaults
in a `*-defaults.properties` file.

---

## Coding conventions

- Immutable domain objects (records)
- No reactive types — virtual threads throughout
- No `@SuppressWarnings` annotations — fix the underlying issue
- `@ConfigurationProperties` as records with defaults in properties files
- Event log implementations extend `AbstractJournal` (key generation)
- Testcontainers 2.x for integration tests, named `*IT`
- Awaitility instead of `Thread.sleep()` in tests
- Apache 2.0 license headers on all files
- Google Java Format via Spotless

---

## Definition of "done" for a spec

A spec is done when ALL of the following are true:

- [ ] The feature described in the spec is implemented
- [ ] All existing tests pass (`./mvnw verify`)
- [ ] New tests exist for the new behavior
- [ ] Spotless passes (`./mvnw spotless:check`)
- [ ] No debug code left in
- [ ] progress.txt is updated with verification results

---

## Constraints and guardrails

- Backend auto-configurations use `@ConditionalOnClass` only — no `@ConditionalOnProperty`
- All new Java files and POM files must include Apache 2.0 license headers
- Tests using Testcontainers must be named `*IT` (Failsafe, not Surefire)
- `@ConfigurationProperties` must be records with defaults in `*-defaults.properties`
- Use `Duration` for all time-based properties — implementations convert internally
- Extend `AbstractJournal` for Journal implementations (shared key generation via UUID v7)
- In-memory fallbacks log WARN when activated

---

## Maven Coordinates

```
groupId:     org.jwcarman.substrate
artifactId:  substrate-parent
version:     1.0.0-SNAPSHOT
Java:        25
Spring Boot: 4.x
```

---

## Future Considerations

- **Change Streams for MongoDB Notifier** — MongoDB Change Streams could serve as
  a notifier. Worth exploring but may have latency concerns.
- **Kafka** — could implement all three SPIs but has a heavy operational footprint.
  Not a priority.
- **Substrate CLI** — a command-line tool for inspecting journals and mailboxes.
  Nice to have, not blocking.
