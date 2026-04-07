# Refactor: Split SPIs from consumer-facing API, add factories

## What to build

Refactor `substrate-core` to separate the backend SPI (key-based, raw) from the
consumer-facing API (bound to a key, friendly). Add factories that produce bound
instances. All existing backend modules update to implement the new SPI interfaces.

### New SPI interfaces (what backend modules implement)

Package: `org.jwcarman.substrate.spi`

Rename the current interfaces to SPI variants:

**JournalSpi** (renamed from `Journal`):
```java
public interface JournalSpi {
    String append(String key, String data);
    String append(String key, String data, Duration ttl);
    Stream<JournalEntry> readAfter(String key, String afterId);
    Stream<JournalEntry> readLast(String key, int count);
    void complete(String key);
    void delete(String key);
}
```

**MailboxSpi** (renamed from `Mailbox`):
```java
public interface MailboxSpi {
    void deliver(String key, String value);
    CompletableFuture<String> await(String key, Duration timeout);
    void delete(String key);
}
```

**NotifierSpi** (renamed from `Notifier`):
```java
public interface NotifierSpi {
    void notify(String key, String payload);
    void subscribe(NotificationHandler handler);
}
```

`NotificationHandler` stays as-is.

`AbstractJournal` becomes `AbstractJournalSpi` — still provides prefix management,
`journalKey(String name)`, and UUID v7 ID generation. All backend implementations
extend this.

`AbstractMailbox` becomes `AbstractMailboxSpi` — still provides prefix management
and `mailboxKey(String name)`.

### Consumer-facing API (what users call)

Package: `org.jwcarman.substrate.core`

**Journal** — bound to a key:
```java
public interface Journal {
    String append(String data);
    String append(String data, Duration ttl);
    Stream<JournalEntry> readAfter(String afterId);
    Stream<JournalEntry> readLast(int count);
    void complete();
    void delete();
    String key();
}
```

**Mailbox** — bound to a key:
```java
public interface Mailbox {
    void deliver(String value);
    CompletableFuture<String> await(Duration timeout);
    void delete();
    String key();
}
```

**Notifier** — unchanged API (not key-bound, it's a broadcast mechanism):
```java
public interface Notifier {
    void notify(String key, String payload);
    void subscribe(NotificationHandler handler);
}
```

Notifier stays the same — it's already consumer-friendly. The `Notifier` interface
in `org.jwcarman.substrate.core` delegates to `NotifierSpi`. Or, since Notifier's
API doesn't change, it can just be an alias/re-export. Simplest: keep `Notifier` as
the single interface (no separate SPI) since there's no key-binding benefit.

### Default implementations

Package: `org.jwcarman.substrate.core`

**DefaultJournal** implements `Journal`:
- Constructor takes `JournalSpi` + `String key`
- Every method delegates to the SPI, passing the bound key

**DefaultMailbox** implements `Mailbox`:
- Constructor takes `MailboxSpi` + `String key`
- Every method delegates to the SPI, passing the bound key

### Factories

Package: `org.jwcarman.substrate.core`

**JournalFactory**:
```java
public class JournalFactory {
    public JournalFactory(JournalSpi journalSpi) { ... }

    public Journal create(String name) {
        return new DefaultJournal(journalSpi, journalSpi.journalKey(name));
    }
}
```

**MailboxFactory**:
```java
public class MailboxFactory {
    public MailboxFactory(MailboxSpi mailboxSpi) { ... }

    public Mailbox create(String name) {
        return new DefaultMailbox(mailboxSpi, mailboxSpi.mailboxKey(name));
    }
}
```

The `journalKey(name)` / `mailboxKey(name)` call stays on the SPI (via
`AbstractJournalSpi` / `AbstractMailboxSpi`) where it applies the backend's
configured prefix. The factory calls it once at creation time.

### Auto-configuration changes

**SubstrateAutoConfiguration** updates:
- In-memory fallbacks now register `InMemoryJournalSpi`, `InMemoryMailboxSpi`,
  `InMemoryNotifier` (InMemoryNotifier name stays since Notifier has no separate SPI)
- New beans: `JournalFactory` (wraps `JournalSpi` bean) and `MailboxFactory`
  (wraps `MailboxSpi` bean)
- `JournalFactory` is `@ConditionalOnBean(JournalSpi.class)`
- `MailboxFactory` is `@ConditionalOnBean(MailboxSpi.class)`

### In-memory implementation renames

- `InMemoryJournal` → `InMemoryJournalSpi`
- `InMemoryMailbox` → `InMemoryMailboxSpi`
- `InMemoryNotifier` stays as-is

### Backend module updates

All backend implementations update:
- `RedisJournal` → `RedisJournalSpi` (implements `JournalSpi`, extends `AbstractJournalSpi`)
- `RedisMailbox` → `RedisMailboxSpi` (implements `MailboxSpi`, extends `AbstractMailboxSpi`)
- `RedisNotifier` stays as-is (implements `Notifier` directly)
- Same pattern for PostgreSQL, Hazelcast, NATS, DynamoDB, MongoDB, Cassandra, RabbitMQ, SNS
- Each backend's auto-config registers the SPI bean (e.g., `RedisJournalSpi` as `JournalSpi`)
- Auto-config class names update accordingly

### Test updates

- All existing tests update to use the new class names
- Add tests for `DefaultJournal` and `DefaultMailbox` (delegation + key binding)
- Add tests for `JournalFactory` and `MailboxFactory`
- Add auto-config tests verifying factory beans are created when SPI beans exist
- Existing backend integration tests can add factory-based usage tests

## Acceptance criteria

- [ ] All SPI interfaces exist in `org.jwcarman.substrate.spi`
- [ ] All consumer-facing interfaces and factories exist in `org.jwcarman.substrate.core`
- [ ] `DefaultJournal` correctly delegates all methods to `JournalSpi` with bound key
- [ ] `DefaultMailbox` correctly delegates all methods to `MailboxSpi` with bound key
- [ ] `JournalFactory.create(name)` returns a `Journal` bound to the prefixed key
- [ ] `MailboxFactory.create(name)` returns a `Mailbox` bound to the prefixed key
- [ ] In-memory implementations renamed and still function correctly
- [ ] All backend modules compile with renamed SPI interfaces
- [ ] All backend auto-configs register SPI beans correctly
- [ ] `SubstrateAutoConfiguration` creates factory beans when SPI beans exist
- [ ] All existing tests pass after renames (updated references)
- [ ] New unit tests for `DefaultJournal`, `DefaultMailbox`, `JournalFactory`, `MailboxFactory`
- [ ] Spotless passes (`./mvnw spotless:check`)
- [ ] Full build passes (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all new/modified files

## Implementation notes

- This is a rename + refactor across all 30 modules. The core logic doesn't change —
  only the interface names, class names, and the new delegation/factory layer.
- `Notifier` does NOT get a separate SPI — its API is already consumer-friendly and
  there's no key-binding benefit. Backend notifier implementations just implement
  `Notifier` directly (or `NotifierSpi` if you prefer consistency — your call).
- The `journalKey(name)` and `mailboxKey(name)` methods stay on the SPI abstract
  classes. The factory calls them to build the full key at creation time.
- `JournalEntry` stays unchanged — it's a data record, not an interface.
