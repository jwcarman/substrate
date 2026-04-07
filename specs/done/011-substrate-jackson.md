# substrate-jackson: Type-safe Journal and Mailbox with Jackson 3 serialization

## What to build

A single Maven module that provides typed wrappers around Substrate's String-based
Journal and Mailbox SPIs, using Jackson 3's `ObjectMapper` for JSON serialization.

Jackson 3.x (package `tools.jackson`) is the version used by Spring Boot 4.x. Key
differences from Jackson 2.x:
- Package moved from `com.fasterxml.jackson` to `tools.jackson`
- `JacksonException` is now unchecked (extends `RuntimeException`) — no need to wrap
- `ObjectMapper` is in `tools.jackson.databind`
- `TypeReference` is in `tools.jackson.core.type`
- `ObjectMapper` is immutable by default; use `JsonMapper.builder()` for configuration

### Module: substrate-jackson

Package: `org.jwcarman.substrate.jackson`

**JacksonJournal<T>** — typed wrapper around `Journal`:
- Constructor takes `Journal`, `ObjectMapper`, and `JavaType` (or `Class<T>`)
- Delegates to the underlying `Journal`, serializing/deserializing via Jackson
- `append(String key, T data)` — serializes `data` to JSON string via
  `objectMapper.writeValueAsString(data)`, delegates to `journal.append(key, json)`
- `append(String key, T data, Duration ttl)` — same with TTL
- `readAfter(String key, String afterId)` — delegates, deserializes each entry's data
  via `objectMapper.readValue(json, javaType)`, returns `Stream<JacksonJournalEntry<T>>`
- `readLast(String key, int count)` — same
- `complete(String key)` — delegates directly
- `delete(String key)` — delegates directly
- `journalKey(String name)` — delegates directly

**JacksonJournalEntry<T>** — typed journal entry:
```java
public record JacksonJournalEntry<T>(String id, String key, T data, Instant timestamp) {}
```

**JacksonMailbox<T>** — typed wrapper around `Mailbox`:
- Constructor takes `Mailbox`, `ObjectMapper`, and `JavaType` (or `Class<T>`)
- `deliver(String key, T value)` — serializes `value` to JSON string via
  `objectMapper.writeValueAsString(value)`, delegates to `mailbox.deliver(key, json)`
- `await(String key, Duration timeout)` — delegates, deserializes the result via
  `objectMapper.readValue(json, javaType)`, returns `CompletableFuture<T>`
- `delete(String key)` — delegates directly
- `mailboxKey(String name)` — delegates directly

**JacksonJournalFactory** — produces typed journals:
```java
public class JacksonJournalFactory {
    public JacksonJournalFactory(Journal journal, ObjectMapper objectMapper) { ... }

    public <T> JacksonJournal<T> create(Class<T> type);
    public <T> JacksonJournal<T> create(TypeReference<T> typeRef);
}
```

**JacksonMailboxFactory** — produces typed mailboxes:
```java
public class JacksonMailboxFactory {
    public JacksonMailboxFactory(Mailbox mailbox, ObjectMapper objectMapper) { ... }

    public <T> JacksonMailbox<T> create(Class<T> type);
    public <T> JacksonMailbox<T> create(TypeReference<T> typeRef);
}
```

The `TypeReference<T>` overloads use Jackson 3's `tools.jackson.core.type.TypeReference`
— no custom type token needed. Usage:

```java
// Simple type
JacksonJournal<MyEvent> journal = journalFactory.create(MyEvent.class);
journal.append(key, myEvent);

// Generic type
JacksonMailbox<List<String>> mailbox = mailboxFactory.create(
    new TypeReference<List<String>>() {});
```

### Auto-configuration

**JacksonSubstrateAutoConfiguration**:
- `@AutoConfiguration`
- `@ConditionalOnClass(ObjectMapper.class)` (`tools.jackson.databind.ObjectMapper`)
- `@ConditionalOnBean({Journal.class, ObjectMapper.class})`
- Creates `JacksonJournalFactory` bean when both `Journal` and `ObjectMapper` exist
- `@ConditionalOnBean({Mailbox.class, ObjectMapper.class})`
- Creates `JacksonMailboxFactory` bean when both `Mailbox` and `ObjectMapper` exist
- Register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

This means: drop `substrate-jackson` + any backend module on the classpath, and you
get typed factories auto-wired with Spring Boot 4's default `ObjectMapper`.

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
- [ ] `complete()`, `delete()`, `journalKey()`, `mailboxKey()` delegate correctly
- [ ] TTL overload on append delegates correctly
- [ ] Jackson 3 `JacksonException` (unchecked) propagates naturally — no wrapping needed
- [ ] `JacksonJournalFactory` creates journals for `Class<T>` and `TypeReference<T>`
- [ ] `JacksonMailboxFactory` creates mailboxes for `Class<T>` and `TypeReference<T>`
- [ ] Auto-config creates `JacksonJournalFactory` when Journal + ObjectMapper beans exist
- [ ] Auto-config creates `JacksonMailboxFactory` when Mailbox + ObjectMapper beans exist
- [ ] Auto-config does NOT create factories when Journal/Mailbox beans are missing
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Module added to `substrate-bom` and parent POM

## Implementation notes

- Jackson 3 package: `tools.jackson.core`, `tools.jackson.databind`,
  `tools.jackson.core.type.TypeReference`
- Dependencies: `substrate-core`, `tools.jackson.core:jackson-databind` (provided by
  Spring Boot 4.x)
- Use `InMemoryJournal` and `InMemoryMailbox` in tests — no Testcontainers needed
- Use `objectMapper.constructType(typeRef)` or
  `objectMapper.getTypeFactory().constructType(typeRef)` to convert `TypeReference`
  to `JavaType` for deserialization
- Jackson 3's `JacksonException` is unchecked (`RuntimeException`), so serialization
  errors propagate naturally without wrapping — cleaner API than Jackson 2
- Serialization uses `objectMapper.writeValueAsString()` since the underlying SPIs
  are String-based
