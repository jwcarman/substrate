# Journal subscribers: reactive callbacks and blocking stream support

**Depends on: spec 013 (byte[] SPIs + Codec integration) must be completed first.**

## What to build

Add a subscription model to `Journal<T>` so consumers can reactively receive new
entries as they arrive, driven by the Notifier. Provide a `JournalSubscriber<T>`
functional interface as the core primitive, plus a `StreamJournalSubscriber<T>`
implementation that bridges reactive callbacks into a blocking `Stream<T>` for
virtual-thread consumers.

### Core subscription API

Package: `org.jwcarman.substrate.core`

**JournalSubscriber<T>** — functional interface for receiving entries:
```java
@FunctionalInterface
public interface JournalSubscriber<T> {
    void onEntry(JournalEntry<T> entry);
}
```

**Subscription** — handle for managing a subscription lifecycle:
```java
public interface Subscription extends AutoCloseable {
    void cancel();

    @Override
    default void close() { cancel(); }
}
```

**Journal<T>** adds subscription methods:
```java
public interface Journal<T> {
    // Existing methods
    String append(T data);
    String append(T data, Duration ttl);
    Stream<JournalEntry<T>> readAfter(String afterId);
    Stream<JournalEntry<T>> readLast(int count);
    void complete();
    void delete();
    String key();

    // New subscription methods
    Subscription subscribe(JournalSubscriber<T> subscriber);
    Subscription subscribe(String afterId, JournalSubscriber<T> subscriber);
}
```

- `subscribe(subscriber)` — subscribes from the current tail (new entries only)
- `subscribe(afterId, subscriber)` — resumes from a cursor (replays missed entries,
  then continues live)

### DefaultJournal<T> subscription implementation

The `DefaultJournal<T>` implements subscriptions using:

1. **Notifier subscription** — registers a `NotificationHandler` that watches for
   notifications matching this journal's key
2. **Virtual thread** — a reader thread that:
   - On startup, reads entries after the cursor via `journalSpi.readAfter()`
   - Delivers each entry to the subscriber via `subscriber.onEntry()`
   - Tracks the cursor (last entry ID seen)
   - On Notifier wake-up, reads new entries from the cursor
   - On journal completion, stops reading
3. **Lifecycle** — `Subscription.cancel()` stops the reader thread and unregisters
   from the Notifier

The Notifier drives the read loop — the reader thread doesn't poll on a timer. It
blocks waiting for Notifier signals, reads all available entries, delivers them,
then blocks again. This is efficient and low-latency.

### StreamJournalSubscriber<T> — blocking stream bridge

Package: `org.jwcarman.substrate.core`

```java
public class StreamJournalSubscriber<T> implements JournalSubscriber<T>, AutoCloseable {
    private final BlockingQueue<JournalEntry<T>> queue;

    public StreamJournalSubscriber();
    public StreamJournalSubscriber(int capacity);

    @Override
    public void onEntry(JournalEntry<T> entry) {
        queue.put(entry);  // blocks if queue is full (backpressure)
    }

    public Stream<JournalEntry<T>> stream();  // blocking, lazy stream

    @Override
    public void close();
}
```

- `stream()` returns a `Stream<JournalEntry<T>>` backed by `queue.take()` — blocks
  waiting for the next entry, terminates when the journal completes or the subscriber
  is closed
- Implements `AutoCloseable` for try-with-resources cleanup
- Default queue capacity is bounded (e.g., 1024) for backpressure

Usage:

```java
Journal<OrderEvent> orders = journalFactory.create("orders:123", OrderEvent.class);

// Reactive — lambda callback
Subscription sub = orders.subscribe(entry -> handleEvent(entry.data()));

// Blocking — streaming subscriber
try (var subscriber = new StreamJournalSubscriber<OrderEvent>()) {
    Subscription sub = orders.subscribe(subscriber);
    subscriber.stream().forEach(entry -> {
        // blocks waiting for next entry
        // terminates when journal.complete() is called
        handleEvent(entry.data());
    });
}
```

### Completion semantics

When the journal producer calls `complete()`:
1. The Notifier fires a signal
2. The reader thread reads remaining entries and delivers them
3. The reader thread detects the completion marker from the SPI
4. For reactive subscribers: the reader thread stops (no more callbacks)
5. For `StreamJournalSubscriber`: a poison pill is pushed into the queue, causing
   `stream()` iteration to terminate naturally

The subscriber can optionally be notified of completion:

```java
public interface JournalSubscriber<T> {
    void onEntry(JournalEntry<T> entry);

    default void onComplete() {}
}
```

### Notifier integration

The `DefaultJournal<T>` needs access to the `Notifier` to subscribe for wake-up
signals. Update the constructor and factory:

**DefaultJournal<T>** constructor:
```java
public DefaultJournal(JournalSpi journalSpi, String key, Codec<T> codec, Notifier notifier)
```

**JournalFactory** now takes a Notifier:
```java
public class JournalFactory {
    public JournalFactory(JournalSpi journalSpi, CodecFactory codecFactory, Notifier notifier) { ... }
}
```

**SubstrateAutoConfiguration** updates the `JournalFactory` bean to inject the
`Notifier` bean alongside `JournalSpi` and `CodecFactory`.

The Journal `append()` method should also notify after appending:
```java
public String append(T data) {
    byte[] bytes = codec.encode(data);
    String entryId = journalSpi.append(key, bytes);
    notifier.notify(key, entryId);
    return entryId;
}
```

This way the producer side automatically wakes up all subscribers.

## Acceptance criteria

- [ ] `JournalSubscriber<T>` functional interface exists with `onEntry` and
  default `onComplete` methods
- [ ] `Subscription` interface exists with `cancel()` and `AutoCloseable` support
- [ ] `Journal<T>.subscribe(subscriber)` creates a live subscription from the tail
- [ ] `Journal<T>.subscribe(afterId, subscriber)` resumes from a cursor
- [ ] Subscriber receives entries as they are appended by the producer
- [ ] Subscriber receives all replayed entries when resuming from a cursor
- [ ] `Subscription.cancel()` stops delivery and cleans up resources
- [ ] `StreamJournalSubscriber<T>` implements `JournalSubscriber<T>`
- [ ] `StreamJournalSubscriber.stream()` returns a blocking `Stream<JournalEntry<T>>`
- [ ] Blocking stream terminates when journal is completed
- [ ] Blocking stream terminates when subscriber is closed
- [ ] Backpressure: `onEntry` blocks when queue is full
- [ ] `DefaultJournal<T>` uses Notifier for wake-up signals (no polling)
- [ ] `Journal.append()` notifies after appending
- [ ] `JournalFactory` accepts and injects `Notifier`
- [ ] Auto-config wires `Notifier` into `JournalFactory`
- [ ] Concurrent test: producer appends in one thread, subscriber receives in another
- [ ] Completion test: producer completes, subscriber stream terminates
- [ ] Cursor resume test: subscriber starts mid-stream, receives remaining + live entries
- [ ] Cancellation test: cancel subscription, no more deliveries
- [ ] Spotless passes (`./mvnw spotless:check`)
- [ ] Full build passes (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all new/modified files

## Implementation notes

- Virtual threads are used for the reader thread — one per subscription. This is
  cheap with Project Loom and matches the "no reactive types" convention.
- The reader thread blocks on a condition/latch waiting for Notifier signals, NOT
  on a timer. The Notifier handler signals the condition when entries arrive.
- `StreamJournalSubscriber` uses a bounded `LinkedBlockingQueue` for backpressure.
  The `stream()` method uses `Stream.generate(() -> queue.take())` with termination
  on a poison pill sentinel.
- The Notifier subscription should filter by key — only wake up when THIS journal's
  key is notified. The current `Notifier.subscribe(handler)` receives all notifications;
  the `DefaultJournal` filters in the handler callback.
- For tests, use `InMemoryJournalSpi` + `InMemoryNotifier` — no Testcontainers needed.
  Use Awaitility for async assertions.
- This spec does NOT add subscription support to Mailbox — `CompletableFuture.join()`
  already covers the blocking case for single-value delivery.
