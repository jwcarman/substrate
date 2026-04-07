# Convert SPIs to byte[], integrate Codec for typed core API

**Depends on: spec 012 (remove substrate-jackson) must be completed first.**

## What to build

This is a combined spec because the byte[] SPI conversion and Codec integration must
happen atomically — the core API needs Codec to compile once SPIs go binary.

Two changes in one:
1. All SPI data parameters change from `String` to `byte[]`
2. Core API becomes typed (`Journal<T>`, `Mailbox<T>`) using Codec for serialization

### Maven dependency

Add to `substrate-core/pom.xml`:
```xml
<dependency>
    <groupId>org.jwcarman.codec</groupId>
    <artifactId>codec-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

Add `codec-core` version to parent POM `<dependencyManagement>`.

### Part 1: SPI changes (byte[])

**JournalSpi** — data becomes `byte[]`:
```java
public interface JournalSpi {
    String append(String key, byte[] data);
    String append(String key, byte[] data, Duration ttl);
    Stream<JournalEntry> readAfter(String key, String afterId);
    Stream<JournalEntry> readLast(String key, int count);
    void complete(String key);
    void delete(String key);
    String journalKey(String name);
}
```

**JournalEntry** — data becomes `byte[]`:
```java
public record JournalEntry(String id, String key, byte[] data, Instant timestamp) {}
```

**MailboxSpi** — value becomes `byte[]`:
```java
public interface MailboxSpi {
    void deliver(String key, byte[] value);
    CompletableFuture<byte[]> await(String key, Duration timeout);
    void delete(String key);
    String mailboxKey(String name);
}
```

**Notifier stays String-based** — it's a signal mechanism ("something changed for
this key"), not data storage.

### Backend implementation changes

Each backend module updates to store/retrieve `byte[]`:

**Redis:**
- Journal: Redis Streams are binary-safe. Store `byte[]` directly as field values via Lettuce.
- Mailbox: Redis GET/SET handles `byte[]` natively via Lettuce.

**PostgreSQL:**
- Journal: Change `data TEXT` to `data BYTEA` in CREATE TABLE script.
  Use `PreparedStatement.setBytes()` / `ResultSet.getBytes()`.
- Mailbox: Change `value TEXT` to `value BYTEA`. Same JDBC approach.

**Hazelcast:**
- Journal: Store `byte[]` directly in Ringbuffer.
- Mailbox: IMap stores `byte[]` values directly.

**NATS:**
- Journal: JetStream messages are `byte[]` natively.
- Mailbox: KV Store values are `byte[]` natively.

**DynamoDB:**
- Journal: Change `data` from String (S) to Binary (B). Use `SdkBytes.fromByteArray()`.
- Mailbox: Change `value` from String (S) to Binary (B).

**MongoDB:**
- Journal: Change `data` from String to `org.bson.types.Binary`.
- Mailbox: Change `value` from String to Binary.

**Cassandra:**
- Journal: Change `data TEXT` to `data BLOB`. Use `ByteBuffer.wrap()`.

**RabbitMQ:**
- Journal: Messages are `byte[]` natively.

**No changes for Notifier backends** — they remain String-based.

### In-memory implementation changes

- `InMemoryJournalSpi`: internal storage changes from `String` to `byte[]`
- `InMemoryMailboxSpi`: internal storage changes from `String` to `byte[]`
- `InMemoryNotifier`: unchanged

### Part 2: Typed core API (Codec integration)

**Journal<T>** — typed, bound to a key:
```java
public interface Journal<T> {
    String append(T data);
    String append(T data, Duration ttl);
    Stream<TypedJournalEntry<T>> readAfter(String afterId);
    Stream<TypedJournalEntry<T>> readLast(int count);
    void complete();
    void delete();
    String key();
}
```

**TypedJournalEntry<T>**:
```java
public record TypedJournalEntry<T>(String id, String key, T data, Instant timestamp) {}
```

**Mailbox<T>** — typed, bound to a key:
```java
public interface Mailbox<T> {
    void deliver(T value);
    CompletableFuture<T> await(Duration timeout);
    void delete();
    String key();
}
```

**DefaultJournal<T>** implements `Journal<T>`:
- Constructor takes `JournalSpi`, `String key`, `Codec<T>`
- `append(T data)` → `codec.encode(data)` → `spi.append(key, bytes)`
- `readAfter(afterId)` → `spi.readAfter(key, afterId)` → map each entry through
  `codec.decode(entry.data())` → `Stream<TypedJournalEntry<T>>`
- Other methods delegate directly

**DefaultMailbox<T>** implements `Mailbox<T>`:
- Constructor takes `MailboxSpi`, `String key`, `Codec<T>`
- `deliver(T value)` → `codec.encode(value)` → `spi.deliver(key, bytes)`
- `await(timeout)` → `spi.await(key, timeout)` → `.thenApply(codec::decode)`
- Other methods delegate directly

### Factories

**JournalFactory**:
```java
public class JournalFactory {
    public JournalFactory(JournalSpi journalSpi, CodecFactory codecFactory) { ... }

    public <T> Journal<T> create(String name, Class<T> type);
    public <T> Journal<T> create(String name, TypeRef<T> typeRef);
}
```

**MailboxFactory**:
```java
public class MailboxFactory {
    public MailboxFactory(MailboxSpi mailboxSpi, CodecFactory codecFactory) { ... }

    public <T> Mailbox<T> create(String name, Class<T> type);
    public <T> Mailbox<T> create(String name, TypeRef<T> typeRef);
}
```

### Auto-configuration changes

**SubstrateAutoConfiguration** updates:
- `JournalFactory` bean requires both `JournalSpi` and `CodecFactory` beans
- `MailboxFactory` bean requires both `MailboxSpi` and `CodecFactory` beans
- If no `CodecFactory` bean exists, no factory beans are created
- In-memory SPI fallbacks still register regardless of CodecFactory presence

### Consumer usage

```java
// Consumer adds: substrate-core + codec-jackson + a backend (e.g., substrate-journal-redis)

@Autowired JournalFactory journalFactory;
@Autowired MailboxFactory mailboxFactory;

// Typed journal
Journal<OrderEvent> orders = journalFactory.create("orders:123", OrderEvent.class);
orders.append(new OrderEvent("created", 42));
Stream<TypedJournalEntry<OrderEvent>> entries = orders.readAfter(cursor);

// Typed mailbox with generics
Mailbox<List<String>> mailbox = mailboxFactory.create(
    "elicit:abc", new TypeRef<List<String>>() {});
mailbox.deliver(List.of("option1", "option2"));
CompletableFuture<List<String>> result = mailbox.await(Duration.ofMinutes(5));
```

## Acceptance criteria

- [ ] `codec-core` 0.1.0 added as dependency to `substrate-core`
- [ ] All SPI interfaces use `byte[]` for data/value parameters
- [ ] `JournalEntry` record uses `byte[]` for data field
- [ ] All backend Journal implementations store and retrieve `byte[]`
- [ ] All backend Mailbox implementations store and retrieve `byte[]`
- [ ] All Notifier implementations remain unchanged (String-based)
- [ ] PostgreSQL schema scripts use BYTEA instead of TEXT for data/value columns
- [ ] DynamoDB uses Binary (B) attribute type
- [ ] MongoDB uses Binary type
- [ ] Cassandra uses BLOB
- [ ] `Journal<T>` interface is generic with typed data
- [ ] `Mailbox<T>` interface is generic with typed value
- [ ] `TypedJournalEntry<T>` record exists with typed data field
- [ ] `DefaultJournal<T>` encodes/decodes via `Codec<T>` correctly
- [ ] `DefaultMailbox<T>` encodes/decodes via `Codec<T>` correctly
- [ ] `JournalFactory` accepts `CodecFactory` and creates typed journals
- [ ] `MailboxFactory` accepts `CodecFactory` and creates typed mailboxes
- [ ] Factory `create(name, Class<T>)` works for simple types
- [ ] Factory `create(name, TypeRef<T>)` works for generic types
- [ ] Auto-config creates factories when both SPI + CodecFactory beans exist
- [ ] Auto-config does NOT create factories when CodecFactory is missing
- [ ] Round-trip tests with typed objects (append → read → assert equal)
- [ ] Round-trip tests with generic types (`List<MyRecord>`, `Map<String, Integer>`)
- [ ] Mailbox deliver/await round-trip with typed objects
- [ ] Spotless passes (`./mvnw spotless:check`)
- [ ] Full build passes (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all new/modified files

## Implementation notes

- No backwards compatibility concerns — project has never been released.
- The SPI change and Codec integration must happen together so the build compiles
  at every commit.
- For core tests, use `codec-jackson` as a test-scoped dependency to get a real
  `CodecFactory`. This matches what consumers will use in practice.
- The `Codec<T>` is obtained once at journal/mailbox creation time (in the factory),
  not on every operation.
- Notifier stays completely unchanged — it's a string-based signal, not data storage.
