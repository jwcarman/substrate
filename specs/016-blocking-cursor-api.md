# Replace subscriber model with blocking cursor API

**Depends on: spec 015 (simplify SPIs) must be completed first.**

## What to build

Remove the reactive subscriber model (JournalSubscriber, Subscription,
StreamJournalSubscriber) and replace it with a simple blocking cursor API.
The entire consumer-facing API becomes synchronous and blocking — designed
for virtual thread environments.

Also rename the journal entry types:
- `JournalEntry` (SPI, `byte[]`) → `RawJournalEntry`
- `TypedJournalEntry<T>` (core, typed) → `JournalEntry<T>`

### What to remove

- `JournalSubscriber<T>` interface
- `Subscription` interface
- `StreamJournalSubscriber<T>` class
- `Journal<T>.subscribe(...)` methods
- Any reactive/callback infrastructure in `DefaultJournal<T>`

### Journal<T> — blocking, cursor-based

```java
public interface Journal<T> {
    // Write
    String append(T data);
    String append(T data, Duration ttl);
    void complete();
    void delete();

    // Read — blocking cursor, driven by Notifier wake-ups
    JournalCursor<T> read();                  // from tail, new entries only
    JournalCursor<T> readAfter(String afterId); // resume from cursor position
    JournalCursor<T> readLast(int count);     // replay last N, then continue live

    String key();
}
```

### JournalCursor<T> — poll-based, closeable, blocking

```java
public interface JournalCursor<T> extends AutoCloseable {
    Optional<JournalEntry<T>> poll(Duration timeout);
    boolean isOpen();
    String lastId();

    @Override
    void close();
}
```

- `poll(Duration timeout)` blocks up to `timeout` waiting for the next entry.
  Returns `Optional.of(entry)` if an entry arrives, `Optional.empty()` on timeout.
  This is the core read operation — consumers build their own loop around it.
- `isOpen()` returns `true` while the cursor is active. Returns `false` when
  either the journal has been completed (and all entries consumed) or the cursor
  has been closed. One flag, one check.
- `lastId()` returns the ID of the last entry returned — used for resume
- `close()` unregisters from the Notifier, interrupts any blocked poll, causes
  `isOpen()` to return `false`
- `AutoCloseable` enables try-with-resources

Usage with SSE keep-alive:
```java
try (var cursor = stream.readAfter(lastEventId)) {
    while (cursor.isOpen()) {
        var entry = cursor.poll(Duration.ofSeconds(30));
        if (entry.isPresent()) {
            writeSseEvent(response, entry.get());
        } else {
            writeSseKeepAlive(response);
        }
    }
}
```

Usage for simple consumption (no keep-alive needed):
```java
try (var cursor = journal.read()) {
    while (cursor.isOpen()) {
        cursor.poll(Duration.ofMinutes(1)).ifPresent(entry -> {
            processEvent(entry.data());
        });
    }
}
```

### DefaultJournalCursor<T> implementation

Internal class in `org.jwcarman.substrate.core`:

- Constructor takes `JournalSpi`, `String key`, `Codec<T>`, `Notifier`, `String afterId`
- Maintains a buffer (`List<RawJournalEntry>`) and a position index
- On `poll(Duration timeout)`:
  1. If buffer has unconsumed entries, return next entry
  2. Call `journalSpi.readAfter(key, lastId)` — get what's there now
  3. If entries found, buffer them, return next entry
  4. If no entries and `journalSpi.isComplete(key)`, set `isOpen()` to false,
     return empty
  5. If no entries and not complete, wait on monitor for Notifier signal up to
     `timeout`. On wake-up, go to step 2. On timeout, return empty.
- The Notifier handler (registered at cursor creation) signals a condition/monitor
  when this journal's key is notified
- `close()` sets `isOpen()` to false, signals the condition, unregisters from Notifier

### Mailbox<T> — poll-based, consistent with JournalCursor

```java
public interface Mailbox<T> {
    void deliver(T value);
    Optional<T> poll(Duration timeout);
    void delete();
    String key();
}
```

- `poll(Duration timeout)` blocks up to `timeout` waiting for a value to be delivered.
  Returns `Optional.of(value)` if delivered, `Optional.empty()` on timeout.
  Same semantics as `JournalCursor.poll()` — no exceptions for timeouts, no futures.
- Implementation: check `mailboxSpi.get()` → if empty, wait for Notifier signal up
  to `timeout` → check again on wake-up → return value or empty on timeout

### JournalEntry<T> and RawJournalEntry

Rename types across the entire codebase:

**SPI layer** (`org.jwcarman.substrate.spi`):
```java
public record RawJournalEntry(String id, String key, byte[] data, Instant timestamp) {}
```

**Core layer** (`org.jwcarman.substrate.core`):
```java
public record JournalEntry<T>(String id, String key, T data, Instant timestamp) {}
```

The clean name goes to what consumers use. The SPI type is an implementation detail.

### JournalSpi update

Change return type to use `RawJournalEntry`:
```java
public interface JournalSpi {
    String append(String key, byte[] data);
    String append(String key, byte[] data, Duration ttl);
    List<RawJournalEntry> readAfter(String key, String afterId);
    List<RawJournalEntry> readLast(String key, int count);
    boolean isComplete(String key);
    void complete(String key);
    void delete(String key);
    String journalKey(String name);
}
```

Note: `readAfter` and `readLast` return `List` instead of `Stream` — the SPI is a
snapshot of "what's there now," not a blocking operation.

### Usage examples

```java
// Odyssey SSE subscriber with keep-alive
void subscribe(HttpServletResponse response, String lastEventId) {
    Journal<OdysseyEvent> stream = journalFactory.create("channel:news", OdysseyEvent.class);
    var cursor = lastEventId != null
        ? stream.readAfter(lastEventId)
        : stream.read();

    try (cursor) {
        while (cursor.isOpen()) {
            var entry = cursor.poll(Duration.ofSeconds(30));
            if (entry.isPresent()) {
                writeSseEvent(response, entry.get());
            } else {
                writeSseKeepAlive(response);
            }
        }
    }
}

// Mocapi elicitation
Mailbox<JsonNode> mailbox = mailboxFactory.create("elicit:abc", JsonNode.class);
Optional<JsonNode> response = mailbox.poll(Duration.ofMinutes(5));
if (response.isPresent()) {
    processResponse(response.get());
} else {
    handleTimeout();
}
```

## Acceptance criteria

- [ ] `JournalSubscriber<T>`, `Subscription`, `StreamJournalSubscriber<T>` removed
- [ ] `Journal<T>.subscribe(...)` methods removed
- [ ] `JournalCursor<T>` interface exists with `poll(Duration)` + `AutoCloseable`
- [ ] `Journal<T>.read()` returns a `JournalCursor<T>` from the tail (new entries only)
- [ ] `Journal<T>.readAfter(afterId)` returns a cursor resuming from a position
- [ ] `Journal<T>.readLast(count)` returns a cursor that replays last N entries then
  continues live
- [ ] `cursor.poll(timeout)` returns `Optional.of(entry)` when entry available
- [ ] `cursor.poll(timeout)` returns `Optional.empty()` on timeout (not an exception)
- [ ] `cursor.isOpen()` returns true while cursor is active
- [ ] `cursor.isOpen()` returns false after journal completes and entries are drained
- [ ] `cursor.isOpen()` returns false after `close()` is called
- [ ] `cursor.poll()` returns empty immediately when cursor is not open
- [ ] `cursor.lastId()` returns the last consumed entry ID
- [ ] Try-with-resources cleanup works (close unregisters from Notifier)
- [ ] `Mailbox<T>.poll(Duration)` blocks and returns `Optional.of(value)` when delivered
- [ ] `Mailbox<T>.poll(Duration)` returns `Optional.empty()` on timeout (not an exception)
- [ ] No `CompletableFuture` in the consumer-facing API
- [ ] Mailbox poll uses same Notifier wake-up pattern as JournalCursor poll
- [ ] `JournalEntry` (SPI) renamed to `RawJournalEntry` across all modules
- [ ] `TypedJournalEntry<T>` (core) renamed to `JournalEntry<T>` across all modules
- [ ] `JournalSpi.readAfter()` and `readLast()` return `List` instead of `Stream`
- [ ] All backend implementations updated for `RawJournalEntry` and `List` return types
- [ ] Concurrent test: producer appends on one thread, cursor iterates on another
- [ ] Completion test: producer completes, cursor.isOpen() becomes false
- [ ] Close test: cursor closed mid-poll, isOpen() becomes false, poll returns empty
- [ ] Resume test: create cursor with `readAfter(lastId)`, receive remaining entries
- [ ] Replay test: `readLast(5)` delivers last 5 entries then transitions to live
- [ ] Mailbox timeout test: no delivery, await throws after timeout
- [ ] Spotless passes (`./mvnw spotless:check`)
- [ ] Full build passes (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all new/modified files

## Implementation notes

- The cursor's blocking `hasNext()` uses `Object.wait()`/`notify()` (or a
  `Lock`/`Condition`) with the Notifier handler calling `notify()` when entries
  arrive. Virtual threads handle the blocking efficiently.
- The Notifier handler filters by key — only wake up for THIS journal's key.
- The cursor maintains an internal buffer from `journalSpi.readAfter()`. It drains
  the buffer before going back to the SPI. This batches reads efficiently.
- `Mailbox.await()` uses the same pattern: wait on monitor, Notifier signals,
  check `mailboxSpi.get()`.
- The timeout exception for Mailbox should be an unchecked Substrate-specific
  exception (e.g., `SubstrateTimeoutException extends RuntimeException`) to keep
  the API clean.
- No reactive types anywhere in the consumer-facing API. Everything is blocking
  and synchronous. Virtual threads make this efficient.
- For tests, use `InMemoryJournalSpi` + `InMemoryNotifier`. Use separate threads
  for producer/consumer with Awaitility for assertions.
