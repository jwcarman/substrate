# substrate-jackson: Type-safe Journal and Mailbox with Jackson 3 serialization

**Depends on: spec 011 (SPI refactor) must be completed first.**

## What to build

A single Maven module that provides typed wrappers around Substrate's consumer-facing
`Journal` and `Mailbox` interfaces, using Jackson 3's `ObjectMapper` for JSON
serialization.

Jackson 3.x (package `tools.jackson`) is the version used by Spring Boot 4.x. Key
differences from Jackson 2.x:
- Package moved from `com.fasterxml.jackson` to `tools.jackson`
- `JacksonException` is now unchecked (extends `RuntimeException`) — no wrapping needed
- `ObjectMapper` is in `tools.jackson.databind`
- `TypeReference` is in `tools.jackson.core.type`

### Module: substrate-jackson

Package: `org.jwcarman.substrate.jackson`

**JacksonJournal<T>** — typed wrapper around `Journal`:
- Constructor takes `Journal` (already bound to a key) and `ObjectMapper` + `JavaType`
- `append(T data)` — serializes via `objectMapper.writeValueAsString(data)`, delegates
  to `journal.append(json)`
- `append(T data, Duration ttl)` — same with TTL
- `readAfter(String afterId)` — delegates, deserializes each entry's data, returns
  `Stream<TypedJournalEntry<T>>`
- `readLast(int count)` — same
- `complete()` — delegates directly
- `delete()` — delegates directly
- `key()` — delegates directly

**TypedJournalEntry<T>** — generic typed entry (lives in `substrate-core` so future
typed wrappers can reuse it):
```java
public record TypedJournalEntry<T>(String id, String key, T data, Instant timestamp) {}
```

**JacksonMailbox<T>** — typed wrapper around `Mailbox`:
- Constructor takes `Mailbox` (already bound to a key) and `ObjectMapper` + `JavaType`
- `deliver(T value)` — serializes via `objectMapper.writeValueAsString(value)`, delegates
  to `mailbox.deliver(json)`
- `await(Duration timeout)` — delegates, deserializes the result, returns
  `CompletableFuture<T>`
- `delete()` — delegates directly
- `key()` — delegates directly

**JacksonJournalFactory** — produces typed journals:
```java
public class JacksonJournalFactory {
    public JacksonJournalFactory(JournalFactory journalFactory, ObjectMapper objectMapper) { ... }

    public <T> JacksonJournal<T> create(String name, Class<T> type);
    public <T> JacksonJournal<T> create(String name, TypeReference<T> typeRef);
}
```

**JacksonMailboxFactory** — produces typed mailboxes:
```java
public class JacksonMailboxFactory {
    public JacksonMailboxFactory(MailboxFactory mailboxFactory, ObjectMapper objectMapper) { ... }

    public <T> JacksonMailbox<T> create(String name, Class<T> type);
    public <T> JacksonMailbox<T> create(String name, TypeReference<T> typeRef);
}
```

The factories wrap `JournalFactory` / `MailboxFactory` from core — they create the
bound instance, then wrap it with Jackson serialization. Usage:

```java
// Simple type
JacksonJournal<MyEvent> journal = jacksonJournalFactory.create("orders:123", MyEvent.class);
journal.append(myEvent);
Stream<TypedJournalEntry<MyEvent>> entries = journal.readAfter(cursor);

// Generic type
JacksonMailbox<List<String>> mailbox = jacksonMailboxFactory.create(
    "elicit:abc", new TypeReference<List<String>>() {});
mailbox.deliver(List.of("a", "b"));
```

### Auto-configuration

**JacksonSubstrateAutoConfiguration**:
- `@AutoConfiguration`
- `@ConditionalOnClass(ObjectMapper.class)` (`tools.jackson.databind.ObjectMapper`)
- Creates `JacksonJournalFactory` bean when `JournalFactory` + `ObjectMapper` beans exist
- Creates `JacksonMailboxFactory` bean when `MailboxFactory` + `ObjectMapper` beans exist
- Register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

## Acceptance criteria

- [ ] Module compiles and produces a jar
- [ ] `AutoConfiguration.imports` registration exists
- [ ] `JacksonJournal<T>` round-trips POJOs through append/readAfter/readLast:
  - Simple record (String/int/boolean fields)
  - Nested records
  - Records with collections (List, Map)
- [ ] `JacksonJournal<T>` via `TypeReference` works for generic types (`List<MyRecord>`)
- [ ] `JacksonMailbox<T>` round-trips POJOs through deliver/await:
  - Simple record
  - Generic type via `TypeReference`
- [ ] `complete()`, `delete()`, `key()` delegate correctly
- [ ] TTL overload on append delegates correctly
- [ ] Jackson 3 `JacksonException` (unchecked) propagates naturally — no wrapping needed
- [ ] `JacksonJournalFactory.create(name, type)` returns a typed journal bound to the key
- [ ] `JacksonMailboxFactory.create(name, typeRef)` returns a typed mailbox bound to the key
- [ ] `TypedJournalEntry<T>` lives in `substrate-core` and is reusable
- [ ] Auto-config creates factories when `JournalFactory`/`MailboxFactory` + `ObjectMapper` exist
- [ ] Auto-config does NOT create factories when dependencies are missing
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Module added to `substrate-bom` and parent POM

## Implementation notes

- Jackson 3 package: `tools.jackson.core`, `tools.jackson.databind`,
  `tools.jackson.core.type.TypeReference`
- Dependencies: `substrate-core`, `tools.jackson.core:jackson-databind` (provided by
  Spring Boot 4.x)
- Use `InMemoryJournalSpi` and `InMemoryMailboxSpi` (via factories) in tests — no
  Testcontainers needed
- Use `objectMapper.constructType(typeRef)` or
  `objectMapper.getTypeFactory().constructType(typeRef)` to convert `TypeReference`
  to `JavaType` for deserialization
- Jackson 3's `JacksonException` is unchecked (`RuntimeException`), so serialization
  errors propagate naturally
- `TypedJournalEntry<T>` goes in `substrate-core` (not `substrate-jackson`) so that
  future typed wrappers (Gson, etc.) can reuse it without depending on Jackson
