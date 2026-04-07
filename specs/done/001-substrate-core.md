# substrate-core: SPIs, in-memory implementations, auto-configuration, and BOM

## What to build

Create the foundational `substrate-core` module, `substrate-bom`, and parent POM for the
Substrate project. This is the base everything else depends on.

### Parent POM

Multi-module Maven project mirroring Odyssey's structure:

- `groupId`: `org.jwcarman.substrate`
- `artifactId`: `substrate-parent`
- `version`: `1.0.0-SNAPSHOT`
- Java 25, Spring Boot 4.x parent
- Plugins: Spotless (Google Java Format, `check` goal at `validate` phase), Surefire
  (with mockito/byte-buddy javaagents), Failsafe, maven-compiler-plugin (Spring Boot
  annotation processors), license-maven-plugin (Apache 2.0 headers)
- Profiles: `release` (Maven Central publishing via sonatype-central-publishing-maven-plugin,
  artifact signing), `ci` (JaCoCo code coverage + Sonar scanning), `license` (header formatting)
- Module list includes `substrate-bom` and `substrate-core` (other modules added by later specs)
- Testcontainers 2.x in dependency management for integration tests across all modules

Use Odyssey's parent POM (`/Users/jcarman/IdeaProjects/odyssey/pom.xml`) as the direct
template for plugin configuration, profiles, and dependency management. This project is
intended for Maven Central publication — mirror Odyssey's release and CI infrastructure
exactly (Sonar, JaCoCo, signing, publishing).

### substrate-bom

A `<dependencyManagement>`-only POM listing all Substrate modules for version alignment.
Start with just `substrate-core`; later specs will add their modules.

### SPI interfaces

Package: `org.jwcarman.substrate.spi`

**Journal** — ordered, append-only, replayable stream (the n-ary case):

```java
public interface Journal {
    String append(String key, String data);                   // uses backend defaultTtl
    String append(String key, String data, Duration ttl);     // caller-specified TTL
    Stream<JournalEntry> readAfter(String key, String afterId);
    Stream<JournalEntry> readLast(String key, int count);
    void complete(String key);
    void delete(String key);
    String journalKey(String name);
}
```

The single-arg `append` delegates to the two-arg form with the backend's configured
`defaultTtl`. This lets consumers like Odyssey set different TTLs per journal flavor
(e.g., ephemeral=5m, channel=1h, broadcast=24h) while keeping a sensible default.

**JournalEntry** — immutable record:

```java
public record JournalEntry(String id, String key, String data, Instant timestamp) {}
```

**Mailbox** — single-value distributed future (the unary case):

```java
public interface Mailbox {
    void deliver(String key, String value);
    CompletableFuture<String> await(String key, Duration timeout);
    void delete(String key);
    String mailboxKey(String name);
}
```

**Notifier** — fire-and-forget signal:

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

### AbstractJournal base class

Package: `org.jwcarman.substrate.spi`

All Journal implementations must extend this. Provides:
- Constructor taking a single `String prefix` parameter
- `String prefix()` accessor
- `String journalKey(String name)` — returns `prefix + name`
- `String generateEntryId()` — UUID v7 via `com.fasterxml.uuid` (time-ordered,
  lexicographically sortable, cluster-safe)

### AbstractMailbox base class

Package: `org.jwcarman.substrate.spi`

All Mailbox implementations must extend this. Provides:
- Constructor taking a single `String prefix` parameter
- `String prefix()` accessor
- `String mailboxKey(String name)` — returns `prefix + name`

### In-memory implementations

Package: `org.jwcarman.substrate.memory`

**InMemoryJournal** — mirrors Odyssey's `InMemoryOdysseyEventLog`:
- `ConcurrentHashMap` with bounded list (configurable `maxLen`, default 100,000)
- Thread-safe via synchronized snapshot reads
- Simple numeric IDs (`"{counter}-0"` format)
- `complete(key)` marks the journal as done (tracked in a `Set`)
- `append(key, data)` delegates to `append(key, data, null)` which uses a configured default TTL
- `append(key, data, ttl)` uses the provided TTL (or default if null)
- `append` on a completed journal throws `IllegalStateException`
- `delete` removes the journal and its completed status

**InMemoryMailbox**:
- `ConcurrentHashMap<String, CompletableFuture<String>>` for pending futures
- `ConcurrentHashMap<String, String>` for delivered values
- `deliver(key, value)` stores the value and completes any pending future
- `await(key, timeout)` returns immediately if value exists, otherwise creates a
  future that completes on delivery or times out
- `delete(key)` removes the value and cancels any pending future

**InMemoryNotifier** — mirrors Odyssey's `InMemoryOdysseyStreamNotifier`:
- `CopyOnWriteArrayList` for handlers
- Synchronous delivery in `notify()`

### Auto-configuration

Package: `org.jwcarman.substrate.autoconfigure`

**SubstrateAutoConfiguration**:
- `@AutoConfiguration`
- Three `@ConditionalOnMissingBean` fallback beans: `InMemoryJournal`,
  `InMemoryMailbox`, `InMemoryNotifier`
- Each fallback logs a WARN: "No Journal/Mailbox/Notifier implementation found;
  using in-memory fallback (single-node only)"
- Register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**SubstrateProperties**:
- `@ConfigurationProperties(prefix = "substrate")` record
- No SPI-specific properties at core level (those live in backend modules)

### Key naming convention

All keys in Substrate use colon-separated segments. Consumers pass logical names
(e.g., `"channel:foo"`, `"elicit:abc123"`). The `journalKey(name)` and `mailboxKey(name)`
methods prepend the backend's configured prefix.

Each backend module translates colons to the platform's idiomatic separator:
- Redis: keeps colons (native)
- NATS: converts to dots (subject hierarchy)
- Others: keeps colons or translates as appropriate

This translation happens in the backend implementations, not in core.

## Acceptance criteria

- [ ] Parent POM builds successfully (`./mvnw validate`)
- [ ] `substrate-bom` POM exists with `substrate-core` in dependency management
- [ ] All three SPI interfaces compile and match the signatures above
- [ ] `AbstractJournal` generates UUID v7 IDs that are lexicographically time-ordered
- [ ] `AbstractMailbox` generates keys with configurable prefix
- [ ] `InMemoryJournal` passes tests: append, readAfter (cursor semantics), readLast,
  complete (blocks further appends), delete, eviction at maxLen, multi-key independence
- [ ] `InMemoryMailbox` passes tests: deliver then await (immediate), await then deliver
  (async), timeout, delete cancels pending future
- [ ] `InMemoryNotifier` passes tests: handler invocation, multiple handlers, no-op with
  no handlers
- [ ] `SubstrateAutoConfiguration` creates fallback beans when no other implementation
  is present (test with `ApplicationContextRunner`)
- [ ] `SubstrateAutoConfiguration` does NOT create fallback beans when a real
  implementation is registered (test with stub beans)
- [ ] Spotless passes (`./mvnw spotless:check`)
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all Java and POM files

## Implementation notes

- Use Odyssey's parent POM as the template: `/Users/jcarman/IdeaProjects/odyssey/pom.xml`
- Use Odyssey's core module as reference: `/Users/jcarman/IdeaProjects/odyssey/odyssey-core/`
- Mirror Odyssey's test patterns: `ApplicationContextRunner` for auto-config tests,
  direct unit tests for in-memory implementations
- The `complete()` method on Journal is new vs. Odyssey — Odyssey doesn't have stream
  completion semantics. This is needed for Odyssey's SSE stream lifecycle.
- Mailbox is entirely new — no Odyssey equivalent to reference
