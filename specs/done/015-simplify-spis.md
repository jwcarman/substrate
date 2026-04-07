# Simplify SPIs: pure storage, no Notifier awareness

**Depends on: spec 014 (journal subscribers) must be completed first.**

## What to build

Refactor the SPIs to be pure storage operations — no Notifier, no threading, no
blocking, no futures. All orchestration (Notifier wake-ups, subscriptions, futures)
moves to the core layer.

Also rename the journal entry types for clarity:
- `JournalEntry` (SPI, `byte[]` data) → `RawJournalEntry`
- `TypedJournalEntry<T>` (core, typed) → `JournalEntry<T>`

This gives the clean name to what consumers actually use.

### JournalSpi — pure append-only log

```java
public interface JournalSpi {
    String append(String key, byte[] data);
    String append(String key, byte[] data, Duration ttl);
    Stream<RawJournalEntry> readAfter(String key, String afterId);
    Stream<RawJournalEntry> readLast(String key, int count);
    boolean isComplete(String key);
    void complete(String key);
    void delete(String key);
    String journalKey(String name);
}
```

Changes:
- Added `isComplete(String key)` — returns whether the journal has been marked complete.
  Core uses this to know when to terminate subscriptions.
- No Notifier reference, no threading, no subscriptions.

### MailboxSpi — pure key-value store

```java
public interface MailboxSpi {
    void deliver(String key, byte[] value);
    Optional<byte[]> get(String key);
    void delete(String key);
    String mailboxKey(String name);
}
```

Changes:
- `await()` replaced with `get()` — returns the value if present, `null` if not.
  No `CompletableFuture`, no blocking, no timeout.
- Core handles the waiting: subscribe to Notifier, on wake-up call `get()`,
  wrap in `CompletableFuture` with timeout.

### Notifier — unchanged

```java
public interface Notifier {
    void notify(String key, String payload);
    void subscribe(NotificationHandler handler);
}
```

Notifier stays as-is. It's already a clean SPI. Backend implementations implement
it directly (no separate `NotifierSpi` needed since the API is already minimal).

### Core orchestration

**DefaultJournal<T>** handles all Notifier integration:
- `append()` calls `journalSpi.append()` then `notifier.notify(key, entryId)`
- `subscribe()` registers a Notifier handler filtered by key, reads from SPI on
  wake-up, delivers to subscriber, checks `isComplete()` for termination
- `readAfter()` / `readLast()` delegate to SPI and decode via Codec (no Notifier)
- `complete()` calls `journalSpi.complete()` then `notifier.notify(key, "completed")`

**DefaultMailbox<T>** handles all Notifier integration:
- `deliver()` calls `mailboxSpi.deliver()` then `notifier.notify(key, "delivered")`
- `await()` checks `mailboxSpi.get()` first (already delivered?), if empty registers
  a Notifier handler filtered by key, on wake-up calls `get()` again, returns
  `CompletableFuture<T>` with timeout support
- `delete()` delegates to SPI

**JournalFactory** and **MailboxFactory** both take `Notifier` in their constructors
and pass it to the default implementations.

### Backend implementation simplifications

All backend Mailbox implementations:
- Remove `CompletableFuture<byte[]> await(...)` method
- Add `byte[] get(String key)` method — simple read operation
- Remove any Notifier references or threading logic
- Remove any polling or blocking logic

All backend Journal implementations:
- Add `boolean isComplete(String key)` method — check for completion marker
- Remove any Notifier references if present

Specific backend changes:

**InMemoryMailboxSpi:**
- Replace `CompletableFuture` tracking with simple `ConcurrentHashMap<String, byte[]>`
- `deliver()` stores the value
- `get()` returns `Optional.of(value)` or `Optional.empty()`
- No threading, no futures

**RedisMailboxSpi:**
- `get()` → Redis GET, return Optional of bytes or empty
- `deliver()` → Redis SET with TTL

**PostgresMailboxSpi:**
- `get()` → `SELECT value FROM ... WHERE key = ?`, return Optional of bytes or empty
- `deliver()` → `INSERT/UPSERT`

**All other Mailbox backends** — same pattern: remove async logic, add simple get.

**All Journal backends** — add `isComplete()` check for completion marker.

## Acceptance criteria

- [ ] `MailboxSpi.await()` removed, replaced with `Optional<byte[]> get(String key)`
- [ ] `JournalSpi.isComplete(String key)` added
- [ ] `JournalEntry` (SPI) renamed to `RawJournalEntry`
- [ ] `TypedJournalEntry<T>` (core) renamed to `JournalEntry<T>`
- [ ] All references across all modules updated (imports, usages, tests)
- [ ] No SPI implementation references `Notifier`
- [ ] No SPI implementation contains threading or blocking logic
- [ ] No SPI implementation returns `CompletableFuture`
- [ ] All Notifier integration lives in `DefaultJournal<T>` and `DefaultMailbox<T>`
- [ ] `DefaultJournal.append()` notifies after appending
- [ ] `DefaultJournal.complete()` notifies after completing
- [ ] `DefaultMailbox.deliver()` notifies after delivering
- [ ] `DefaultMailbox.await()` uses Notifier for wake-up + `spi.get()` for read
- [ ] `DefaultMailbox.await()` returns immediately if value already delivered
- [ ] `DefaultMailbox.await()` respects timeout via `CompletableFuture.orTimeout()`
- [ ] `InMemoryMailboxSpi` is simplified to a plain `ConcurrentHashMap`
- [ ] All backend Mailbox implementations simplified (no futures, no threading)
- [ ] All backend Journal implementations add `isComplete()` 
- [ ] All existing tests pass after refactor
- [ ] New tests for core orchestration (Notifier-driven wake-up, timeout, completion)
- [ ] Spotless passes (`./mvnw spotless:check`)
- [ ] Full build passes (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all new/modified files

## Implementation notes

- This is a simplification refactor — backend implementations get simpler, core gets
  the orchestration logic.
- The key insight: SPIs are repositories (read/write data). Core is the service layer
  (orchestrates Notifier + SPI + Codec + threading).
- `InMemoryMailboxSpi` becomes trivially simple — just a map. All the "wait for value"
  complexity moves to `DefaultMailbox<T>` in core, where it uses the `InMemoryNotifier`
  for wake-ups just like any other backend.
- Backend integration tests that tested `await()` behavior should move to core tests
  that test `DefaultMailbox<T>` with in-memory SPI + Notifier.
- No backwards compatibility concerns — project has never been released.
