# Getting Started

This guide adds Substrate to a Spring Boot application, creates one of each primitive, and
explains what happens when no backend is configured.

## Requirements

- Java 25 or later
- Spring Boot 4.x, for auto-configuration (the primitives also work without Spring; see
  [Without Spring Boot](#without-spring-boot))
- A [Codec](https://jwcarman.github.io/codec/) backend. Substrate stores bytes, and Codec turns
  your values into those bytes.

## Dependencies

Import the Substrate BOM, then add three things: `substrate-core`, a codec, and a backend
module.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.jwcarman.substrate</groupId>
            <artifactId>substrate-bom</artifactId>
            <version>0.11.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.jwcarman.codec</groupId>
            <artifactId>codec-bom</artifactId>
            <version>0.10.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.jwcarman.substrate</groupId>
        <artifactId>substrate-core</artifactId>
    </dependency>

    <!-- The codec: the starter registers a CodecFactory bean, the backend serializes -->
    <dependency>
        <groupId>org.jwcarman.codec</groupId>
        <artifactId>codec-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.jwcarman.codec</groupId>
        <artifactId>codec-jackson</artifactId>
    </dependency>

    <!-- One backend module; this one supports all three primitives -->
    <dependency>
        <groupId>org.jwcarman.substrate</groupId>
        <artifactId>substrate-redis</artifactId>
    </dependency>
</dependencies>
```

!!! warning "The codec starter is required"
    Substrate registers its factories only when a `CodecFactory` bean exists. With
    `codec-jackson` alone, nothing registers that bean: the application starts, but there is no
    `AtomFactory`, `JournalFactory` or `MailboxFactory` to inject. `codec-spring-boot-starter`
    provides the bean.

Backend modules are organised one per technology, not one per primitive. `substrate-redis`
supplies Atom, Journal and Mailbox at once. You can also combine modules, for example Redis for
Atom and Mailbox and PostgreSQL for Journal; see [Choosing a Backend](../backends/index.md).

There is no `@EnableSubstrate` and no bean wiring. Each backend module is a Spring Boot
auto-configuration: on the classpath, it registers itself. It connects using the connection
Spring Boot already configures for that technology — for Redis, the `spring.data.redis.*`
properties. Each [backend page](../backends/index.md) lists what it needs.

## A first Atom

An Atom holds one current value under a name, shared by every node. Inject the factory and
create one:

```java
@Service
class PresenceService {

  private final AtomFactory atoms;

  PresenceService(AtomFactory atoms) {
    this.atoms = atoms;
  }

  void join(String userId) {
    Atom<Presence> presence =
        atoms.create("presence:" + userId, Presence.class, Presence.online(), Duration.ofMinutes(5));
    // ...
  }
}
```

`create` writes the value immediately and starts a five-minute lease. The atom expires unless
`set` or `touch` renews it. Any node can read it:

```java
Atom<Presence> presence = atoms.connect("presence:" + userId, Presence.class);
Snapshot<Presence> current = presence.get();
Presence value = current.value();
```

`connect` does no I/O itself. The first operation checks that the atom exists, and throws
`AtomNotFoundException` if it doesn't.

## A first Journal

A Journal is an ordered stream. Producers append; consumers read from the tail, from the last
*N* entries, or after an entry ID they saved.

```java
Journal<OrderEvent> journal =
    journals.create("orders:" + orderId, OrderEvent.class, Duration.ofHours(1));

String id = journal.append(new OrderEvent("created"), Duration.ofDays(1));
```

The first `Duration` is the journal's inactivity TTL: if nothing is appended for an hour, it
expires — on PostgreSQL and the in-memory backend. Redis and most other backends don't enforce it;
see [How backends differ](../primitives/journal.md#how-backends-differ). The second is how long this entry is kept. A consumer, on any node, tails it from a
virtual thread:

```java
try (BlockingSubscription<JournalEntry<OrderEvent>> sub = journal.subscribe()) {
  while (sub.isActive()) {
    switch (sub.next(Duration.ofSeconds(30))) {
      case NextResult.Value<JournalEntry<OrderEvent>>(var entry) -> handle(entry.data());
      case NextResult.Timeout<JournalEntry<OrderEvent>> _ -> { /* nothing new yet */ }
      case NextResult.Completed<JournalEntry<OrderEvent>> _ -> log.info("journal completed");
      case NextResult.Expired<JournalEntry<OrderEvent>> _ -> log.info("journal expired");
      case NextResult.Deleted<JournalEntry<OrderEvent>> _ -> log.info("journal deleted");
      case NextResult.Errored<JournalEntry<OrderEvent>>(var cause) -> log.error("failed", cause);
      case NextResult.Cancelled<JournalEntry<OrderEvent>> _ -> { /* cancelled locally */ }
    }
  }
}
```

The `switch` names all seven outcomes, so the compiler checks that none is forgotten.
[Subscriptions](../concepts/index.md#subscriptions) explains each one.

## A first Mailbox

A Mailbox carries one value, from whichever node has it to whichever node is waiting.

```java
// The node handling the request creates the mailbox and waits.
Mailbox<Approval> mailbox =
    mailboxes.create("approval:" + requestId, Approval.class, Duration.ofMinutes(5));

try (BlockingSubscription<Approval> sub = mailbox.subscribe()) {
  if (sub.next(Duration.ofMinutes(5)) instanceof NextResult.Value<Approval>(var approval)) {
    proceed(approval);
  }
}
```

```java
// Any node delivers the answer.
mailboxes.connect("approval:" + requestId, Approval.class).deliver(approval);
```

A mailbox accepts one delivery. A second `deliver` throws `MailboxFullException`.

## Without a backend

If no module on the classpath supports a primitive, Substrate registers an in-memory
implementation for it and logs a warning at startup:

```text
No Journal implementation found; using in-memory fallback (single-node only). For clustered
deployments, add a backend module (e.g. substrate-redis).
```

The in-memory backend behaves like the others — TTLs, completion, deletion and subscriptions all
work — but its state lives in one JVM. It is for development and tests, and for primitives a
single-node application uses. See [In-Memory](../backends/in-memory.md) and
[Testing](testing.md).

## Without Spring Boot

The factories are plain classes, so the primitives work without Spring. You assemble what
auto-configuration would: a backend SPI, a codec, a notifier and a shutdown coordinator. Here,
with the in-memory backend:

```java
CodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
Notifier notifier = new DefaultNotifier(new InMemoryNotifier(), codecs);
ShutdownCoordinator shutdown = new ShutdownCoordinator();

AtomFactory atoms =
    new DefaultAtomFactory(
        new InMemoryAtomSpi(),
        codecs,
        PayloadTransformer.IDENTITY,
        notifier,
        Duration.ofHours(24), // the longest TTL an atom may be given
        shutdown);
```

`DefaultJournalFactory` and `DefaultMailboxFactory` are built the same way;
`DefaultJournalFactory` takes a `JournalLimits` (`JournalLimits.defaults()`) in place of the
maximum TTL. Outside Spring, nothing calls `shutdown.stop()` for you, and nothing sweeps expired
records; [Operating in Production](operating.md) explains what both do.

## Next

- [Concepts](../concepts/index.md) — the subscription model, lifecycles and TTLs, once for all
  three primitives.
- [Atom](../primitives/atom.md), [Journal](../primitives/journal.md),
  [Mailbox](../primitives/mailbox.md) — each primitive in full.
- [Choosing a Backend](../backends/index.md).
