# Refine nudge mechanism: semaphore + reader thread for Journal, direct semaphore for Mailbox

**Depends on: spec 016 (blocking cursor API) must be completed first.**

## What to build

Refine the internal nudge/read implementation to use the correct concurrency
primitives. This is an internal refactor — no API changes.

### Journal: reader virtual thread + BlockingQueue

Each `JournalCursor<T>` spins up a dedicated reader virtual thread. The consumer
polls a `BlockingQueue`. This separates the Notifier-driven read loop from the
consumer's poll loop and avoids ordering/duplication issues.

**Reader virtual thread (per cursor):**
```java
Thread.ofVirtual().start(() -> {
    while (isOpen()) {
        // "Just in case" read — catches data from missed nudges
        var entries = journalSpi.readAfter(key, lastId);
        for (var entry : entries) {
            queue.put(decode(entry));
            lastId = entry.id();
        }
        if (journalSpi.isComplete(key) && entries.isEmpty()) {
            // Push poison pill to signal completion
            break;
        }
        // Wait for nudge or timeout
        semaphore.tryAcquire(pollTimeout, TimeUnit.MILLISECONDS);
        semaphore.drainPermits();  // clear stacked nudges
    }
});
```

**Notifier handler:**
```java
notifier.subscribe((key, payload) -> {
    if (key.equals(this.key)) {
        semaphore.release();
    }
});
```

**Consumer-facing `poll(Duration timeout)`:**
```java
public Optional<JournalEntry<T>> poll(Duration timeout) {
    var entry = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    return Optional.ofNullable(entry);
}
```

**Key details:**
- `Semaphore(0)` — Notifier handler calls `release()`, reader thread calls
  `tryAcquire(timeout)` then `drainPermits()` after waking up
- `drainPermits()` avoids wasted no-op SPI reads from stacked nudges
- `BlockingQueue` (bounded `LinkedBlockingQueue`, default capacity 1024) sits
  between the reader thread and the consumer
- The reader thread always does a "just in case" `readAfter` before waiting on
  the semaphore — catches data from nudges that arrived before the handler was
  registered or between reads
- One reader virtual thread per cursor — lightweight, dies when cursor closes
- Ordering is guaranteed — single reader thread serializes all SPI reads
- No duplicates — single reader thread tracks `lastId` exclusively
- Poison pill (e.g., a sentinel value or separate `AtomicBoolean`) signals
  journal completion through the queue to the consumer
- `cursor.close()` sets `isOpen()` to false, calls `semaphore.release()` to
  unblock the reader thread, and the reader thread exits its loop

**Backpressure:** If the consumer is slow, the queue fills up and `queue.put()`
blocks the reader thread. The SPI stops being read until the consumer catches up.
This is natural backpressure — no data loss, just delayed reads.

### Mailbox: reader virtual thread + CompletableFuture

Same dedicated reader thread pattern as Journal, but with a `CompletableFuture<T>`
instead of a `BlockingQueue` — because Mailbox is single-value.

**Reader virtual thread (per mailbox):**
```java
Thread.ofVirtual().start(() -> {
    while (!future.isDone()) {
        // "Just in case" read
        var value = mailboxSpi.get(key);
        if (value.isPresent()) {
            future.complete(codec.decode(value.get()));
            return;
        }
        // Wait for nudge
        semaphore.tryAcquire(pollTimeout, TimeUnit.MILLISECONDS);
        semaphore.drainPermits();
    }
});
```

**Notifier handler:**
```java
notifier.subscribe((key, payload) -> {
    if (key.equals(this.key)) {
        semaphore.release();
    }
});
```

**Consumer-facing `poll(Duration timeout)`:**
```java
public Optional<T> poll(Duration timeout) {
    try {
        T value = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return Optional.of(value);
    } catch (TimeoutException e) {
        return Optional.empty();
    }
}
```

**Key details:**
- Dedicated reader virtual thread — same pattern as Journal
- `CompletableFuture<T>` is the single-value equivalent of `BlockingQueue`
- Reader thread loops checking SPI on each nudge, completes the future when value found
- `future.get(timeout)` handles the consumer's poll timeout naturally
- "Just in case" read before waiting catches already-delivered values
- `drainPermits()` avoids wasted reads from stacked nudges
- Reader thread exits once `future.complete()` is called
- `mailbox.delete()` can cancel the future and release the semaphore to clean up

### Write-then-nudge guarantee

The core API guarantees data is persisted before the nudge fires:

```java
// DefaultJournal.append()
String entryId = journalSpi.append(key, codec.encode(data));  // data persisted
notifier.notify(key, entryId);                                  // THEN nudge
return entryId;

// DefaultMailbox.deliver()
mailboxSpi.deliver(key, codec.encode(value));  // data persisted
notifier.notify(key, "delivered");              // THEN nudge
```

The SPI write is synchronous — it returns after the backend confirms persistence.
The nudge cannot race the data.

## Acceptance criteria

- [ ] `JournalCursor` uses a dedicated reader virtual thread per cursor
- [ ] Reader thread uses `Semaphore(0)` for nudge signaling
- [ ] Reader thread calls `drainPermits()` after acquiring to clear stacked nudges
- [ ] Reader thread always does a "just in case" `readAfter` before waiting on semaphore
- [ ] Reader thread pushes decoded entries into a bounded `BlockingQueue`
- [ ] `JournalCursor.poll(timeout)` delegates to `queue.poll(timeout)`
- [ ] Journal completion pushes a sentinel through the queue, `isOpen()` becomes false
- [ ] `cursor.close()` releases semaphore permit to unblock reader thread
- [ ] Reader virtual thread exits cleanly on cursor close
- [ ] Entry ordering is preserved (single reader thread serializes reads)
- [ ] No duplicate entries (single reader thread tracks lastId)
- [ ] Backpressure: full queue blocks the reader thread, not the consumer
- [ ] `Mailbox` uses a dedicated reader virtual thread (same pattern as Journal)
- [ ] Mailbox reader thread uses `CompletableFuture<T>` instead of `BlockingQueue`
- [ ] Mailbox reader thread completes the future when `mailboxSpi.get()` returns a value
- [ ] Mailbox reader thread exits after completing the future
- [ ] `Mailbox.poll(timeout)` delegates to `future.get(timeout)`
- [ ] Mailbox reader does "just in case" SPI read before waiting on semaphore
- [ ] `drainPermits()` used on both Journal and Mailbox
- [ ] `append()` and `deliver()` persist data before firing nudge
- [ ] No `synchronized` blocks or `Object.wait()` — virtual-thread safe throughout
- [ ] Concurrent test: producer appends rapidly, consumer polls — all entries received in order
- [ ] Stacked nudge test: multiple rapid appends, reader batches them efficiently
- [ ] Missed nudge test: data persisted before handler registered, "just in case" read finds it
- [ ] Backpressure test: slow consumer, queue fills, reader blocks, no data loss
- [ ] Mailbox poll-before-deliver test: deliver first, poll finds it immediately
- [ ] Mailbox poll-then-deliver test: poll waits, deliver nudges, poll returns value
- [ ] Spotless passes (`./mvnw spotless:check`)
- [ ] Full build passes (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all new/modified files

## Implementation notes

- Virtual threads are essentially free — one per cursor is fine even with thousands
  of concurrent cursors.
- The reader thread's poll timeout on the semaphore can be a fixed internal value
  (e.g., 1 second) independent of the consumer's poll timeout. This controls how
  often the "just in case" read happens during idle periods.
- The `BlockingQueue` capacity (default 1024) is a reasonable default. Could be
  configurable if needed, but start with a sensible default.
- `LinkedBlockingQueue` is the right choice — unbounded put with bounded capacity,
  virtual-thread safe.
- The poison pill for journal completion can be a private sentinel object checked
  inside `poll()`, or a separate `AtomicBoolean` that `poll()` checks when the
  queue is empty.
- Notifier handler filtering by key means each cursor only wakes up for its own
  journal. No broadcast storm from unrelated journals.
