# Journal subscription migration

**Depends on: spec 033 (subscription foundation) must be completed
first.** This spec migrates Journal from the existing
`JournalCursor<T>`-based API to the new `Subscription`-based model
built on `BlockingBoundedHandoff` (with configurable capacity).

## What to build

Replace the cursor-returning `read` / `readAfter` / `readLast`
methods with the new subscription model:

- Add nine `subscribe` methods to the `Journal<T>` interface (three
  blocking variants + six callback variants, one blocking and two
  callback variants per starting-position: current tail, after id,
  last N)
- Remove `Journal.read()`, `Journal.readAfter(String)`, and
  `Journal.readLast(int)` from the interface
- Delete the `JournalCursor<T>` interface from `substrate-api`
- Rewrite `DefaultJournal<T>` to construct a
  `BlockingBoundedHandoff<JournalEntry<T>>` with the configured
  capacity and spin up a feeder virtual thread with checkpoint
  tracking
- Delete `DefaultJournalCursor<T>` — its machinery is folded into the
  new feeder thread pattern
- Publish a `"__DELETED__"` notification payload on `delete()` so
  subscribers detect explicit deletion immediately
- Migrate every call site that used `journal.read()` /
  `readAfter()` / `readLast()` cursors to the new API

Scope is strictly Journal. Atom and Mailbox are unaffected by this
spec.

## `Journal<T>` interface changes

```java
package org.jwcarman.substrate.journal;

import java.time.Duration;
import java.util.function.Consumer;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;

public interface Journal<T> {

  // ═══════════════ writes (unchanged from spec 024) ═══════════════
  String append(T data, Duration entryTtl);
  void complete(Duration retentionTtl);
  void delete();

  // ═══════════════ blocking subscribe (NEW) ═══════════════

  /**
   * Subscribe from the current tail. Only entries appended after this
   * call are delivered. Historical entries (those already in the
   * journal when {@code subscribe} is called) are NOT replayed.
   */
  BlockingSubscription<JournalEntry<T>> subscribe();

  /**
   * Subscribe starting strictly after {@code afterId}. All entries
   * with an id greater than {@code afterId} are delivered, followed
   * by new entries as they arrive. Useful for resuming from a
   * persisted checkpoint.
   */
  BlockingSubscription<JournalEntry<T>> subscribeAfter(String afterId);

  /**
   * Subscribe starting with the last {@code count} retained entries,
   * then continue with new entries as they arrive. Useful for
   * "show the last N events, then tail" patterns.
   */
  BlockingSubscription<JournalEntry<T>> subscribeLast(int count);

  // ═══════════════ callback subscribe (NEW) ═══════════════

  /** Callback subscribe from current tail with only onNext. */
  CallbackSubscription subscribe(Consumer<JournalEntry<T>> onNext);

  /** Callback subscribe from current tail with additional handlers. */
  CallbackSubscription subscribe(
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer);

  /** Callback subscribe from a checkpoint with only onNext. */
  CallbackSubscription subscribeAfter(
      String afterId,
      Consumer<JournalEntry<T>> onNext);

  /** Callback subscribe from a checkpoint with additional handlers. */
  CallbackSubscription subscribeAfter(
      String afterId,
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer);

  /** Callback subscribe from last N entries with only onNext. */
  CallbackSubscription subscribeLast(
      int count,
      Consumer<JournalEntry<T>> onNext);

  /** Callback subscribe from last N entries with additional handlers. */
  CallbackSubscription subscribeLast(
      int count,
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer);

  // ═══════════════ identification (unchanged) ═══════════════
  String key();
}
```

### Removed from `Journal`

- `JournalCursor<T> read()`
- `JournalCursor<T> readAfter(String afterId)`
- `JournalCursor<T> readLast(int count)`

### Deleted from `substrate-api`

- `org.jwcarman.substrate.journal.JournalCursor<T>` — the interface is
  deleted entirely. Its role is replaced by
  `BlockingSubscription<JournalEntry<T>>`.

## `DefaultJournal<T>` rewrite

Replace the cursor-returning methods with subscribe methods that
construct a `BlockingBoundedHandoff<JournalEntry<T>>` with the
configured capacity and spin up a feeder virtual thread.

### Configuration lookup

The subscription queue capacity comes from `SubstrateProperties`
(which spec 033 already extended with
`JournalProperties.subscription.queueCapacity`, default 1024).
`DefaultJournal` stores the capacity value at construction time
(via its factory) and uses it for every `subscribe` call:

```java
public class DefaultJournal<T> implements Journal<T> {

  private final JournalSpi journalSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;
  private final int subscriptionQueueCapacity;   // from properties
  private final Duration maxInactivityTtl;        // from properties, spec 019
  private final Duration maxRetentionTtl;         // from properties, spec 019
  private final Duration maxEntryTtl;             // from properties, spec 019

  public DefaultJournal(
      JournalSpi journalSpi,
      String key,
      Codec<T> codec,
      NotifierSpi notifier,
      int subscriptionQueueCapacity,
      Duration maxInactivityTtl,
      Duration maxRetentionTtl,
      Duration maxEntryTtl) {
    // ...
  }

  // ... append, complete, delete, key ...
}
```

### `subscribe()` — from current tail

```java
@Override
public BlockingSubscription<JournalEntry<T>> subscribe() {
  // Current tail id = id of the most recent entry at subscription time.
  // If the journal is empty, tailId is null, which readAfter treats
  // as "from the beginning" — but since the journal is empty, that's
  // equivalent to "from the current tail" anyway.
  List<RawJournalEntry> lastEntries = journalSpi.readLast(key, 1);
  String startingCheckpoint = lastEntries.isEmpty()
      ? null
      : lastEntries.get(lastEntries.size() - 1).id();
  return buildBlockingSubscription(startingCheckpoint, List.of());
}
```

### `subscribeAfter(afterId)`

```java
@Override
public BlockingSubscription<JournalEntry<T>> subscribeAfter(String afterId) {
  return buildBlockingSubscription(afterId, List.of());
}
```

### `subscribeLast(count)`

```java
@Override
public BlockingSubscription<JournalEntry<T>> subscribeLast(int count) {
  List<RawJournalEntry> preload = journalSpi.readLast(key, count);
  String startingCheckpoint = preload.isEmpty()
      ? null
      : preload.get(preload.size() - 1).id();
  return buildBlockingSubscription(startingCheckpoint, preload);
}
```

The `preload` list is pushed into the handoff before the feeder
thread starts its normal loop — this delivers the "last N entries"
immediately, and then the feeder continues from the checkpoint
(which is the id of the last preloaded entry).

### Callback variants

Same pattern as Atom — delegate to a helper that builds a
`CoalescingHandoff`... wait, Journal uses `BlockingBoundedHandoff`,
not `Coalescing`. Let me restate: delegate to a helper that builds a
`BlockingBoundedHandoff` and wraps it in a
`DefaultCallbackSubscription`:

```java
@Override
public CallbackSubscription subscribe(Consumer<JournalEntry<T>> onNext) {
  List<RawJournalEntry> lastEntries = journalSpi.readLast(key, 1);
  String startingCheckpoint = lastEntries.isEmpty()
      ? null
      : lastEntries.get(lastEntries.size() - 1).id();
  return buildCallbackSubscription(startingCheckpoint, List.of(), onNext, null);
}

// ... similar for the other five callback variants, varying only in
// how startingCheckpoint and preload are computed ...
```

### `buildBlockingSubscription` helper

```java
private BlockingSubscription<JournalEntry<T>> buildBlockingSubscription(
    String startingCheckpoint,
    List<RawJournalEntry> preload) {
  BlockingBoundedHandoff<JournalEntry<T>> handoff =
      new BlockingBoundedHandoff<>(subscriptionQueueCapacity);
  Runnable canceller = startFeeder(handoff, startingCheckpoint, preload);
  return new DefaultBlockingSubscription<>(handoff, canceller);
}

private CallbackSubscription buildCallbackSubscription(
    String startingCheckpoint,
    List<RawJournalEntry> preload,
    Consumer<JournalEntry<T>> onNext,
    Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer) {
  BlockingBoundedHandoff<JournalEntry<T>> handoff =
      new BlockingBoundedHandoff<>(subscriptionQueueCapacity);
  Runnable canceller = startFeeder(handoff, startingCheckpoint, preload);

  DefaultCallbackSubscriberBuilder<JournalEntry<T>> builder =
      new DefaultCallbackSubscriberBuilder<>();
  if (customizer != null) customizer.accept(builder);

  return new DefaultCallbackSubscription<>(
      handoff,
      canceller,
      onNext,
      builder.errorHandler(),
      builder.expirationHandler(),
      builder.deleteHandler(),
      builder.completeHandler());
}
```

### The feeder thread — the heart of the spec

The feeder is responsible for:

1. **Pushing preloaded entries first** (for `subscribeLast` scenarios)
2. **Maintaining a monotonic checkpoint** — the id of the last entry
   successfully pushed to the handoff
3. **Reading after the checkpoint** on every wake-up
4. **Detecting completion** and draining remaining entries before
   marking the handoff complete
5. **Handling expiration** via `JournalExpiredException`
6. **Handling deletion** via the `"__DELETED__"` notification payload
7. **Sleeping on the semaphore** when there's nothing to do
8. **Coalescing nudges** via `drainPermits()` after wake-up

```java
private Runnable startFeeder(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    String startingCheckpoint,
    List<RawJournalEntry> preload) {

  AtomicBoolean running = new AtomicBoolean(true);
  Semaphore semaphore = new Semaphore(0);
  AtomicReference<String> checkpoint = new AtomicReference<>(startingCheckpoint);

  NotifierSubscription notifierSub = notifier.subscribe((notifiedKey, payload) -> {
    if (!key.equals(notifiedKey)) return;
    if ("__DELETED__".equals(payload)) {
      handoff.markDeleted();
      running.set(false);
    }
    semaphore.release();
  });

  Thread feederThread = Thread.ofVirtual()
      .name("substrate-journal-feeder", 0)
      .start(() -> {
        try {
          // First: push any preloaded entries (subscribeLast scenario).
          for (RawJournalEntry raw : preload) {
            if (!running.get()) return;
            handoff.push(decode(raw));
            checkpoint.set(raw.id());
          }

          // Main loop: read-after-checkpoint, push, park on semaphore.
          while (running.get() && !Thread.currentThread().isInterrupted()) {

            // "Just in case" read — catches entries from any nudges
            // that arrived before the subscription registered, and
            // entries added by other nodes between our previous read
            // and now.
            List<RawJournalEntry> batch =
                journalSpi.readAfter(key, checkpoint.get());

            for (RawJournalEntry raw : batch) {
              if (!running.get()) return;
              handoff.push(decode(raw));   // may block on full queue — backpressure
              checkpoint.set(raw.id());     // advance checkpoint after successful push
            }

            // Check for natural completion.
            if (journalSpi.isComplete(key)) {
              // Final drain: catch any entries appended between the
              // previous batch and the completion marker being set.
              List<RawJournalEntry> finalBatch =
                  journalSpi.readAfter(key, checkpoint.get());
              for (RawJournalEntry raw : finalBatch) {
                if (!running.get()) return;
                handoff.push(decode(raw));
                checkpoint.set(raw.id());
              }
              handoff.markCompleted();
              return;
            }

            // Park on the semaphore until a notification wakes us up
            // or the poll interval elapses. drainPermits coalesces
            // multiple stacked nudges into a single wake-up.
            semaphore.tryAcquire(1, TimeUnit.SECONDS);
            semaphore.drainPermits();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (JournalExpiredException e) {
          handoff.markExpired();
        } catch (RuntimeException e) {
          handoff.error(e);
        } finally {
          notifierSub.cancel();
        }
      });

  return () -> {
    running.set(false);
    feederThread.interrupt();
    notifierSub.cancel();
  };
}

private JournalEntry<T> decode(RawJournalEntry raw) {
  return new JournalEntry<>(
      raw.id(),
      raw.key(),
      codec.decode(raw.data()),
      raw.timestamp());
}
```

### `delete()` publishes `"__DELETED__"`

```java
@Override
public void delete() {
  journalSpi.delete(key);
  notifier.notify(key, "__DELETED__");
}
```

Subscribers' feeder threads see the `"__DELETED__"` payload in the
notifier handler and call `handoff.markDeleted()` immediately.

## Removals

- **`JournalCursor<T>` interface** — deleted from
  `substrate-api/.../journal/JournalCursor.java`.
- **`DefaultJournalCursor<T>` class** — deleted from
  `substrate-core/.../core/journal/DefaultJournalCursor.java`. Its
  reader-thread machinery is folded into the new feeder thread
  pattern.
- **`Journal.read()`, `Journal.readAfter(String)`, `Journal.readLast(int)`** —
  removed from the `Journal<T>` interface and from `DefaultJournal`.

## Test migration

Every existing test that used `journal.read()` / `readAfter()` /
`readLast()` cursors needs a rewrite. The pattern:

```java
// Before
try (JournalCursor<Event> cursor = journal.read()) {
  while (running) {
    Optional<JournalEntry<Event>> entry = cursor.poll(Duration.ofSeconds(30));
    entry.ifPresent(e -> process(e.data()));
  }
}

// After — blocking
try (BlockingSubscription<JournalEntry<Event>> sub = journal.subscribe()) {
  loop: while (sub.isActive()) {
    switch (sub.next(Duration.ofSeconds(30))) {
      case NextResult.Value<JournalEntry<Event>>(var entry) -> process(entry.data());
      case NextResult.Timeout<JournalEntry<Event>> t -> {}
      case NextResult.Completed<JournalEntry<Event>> c -> break loop;
      case NextResult.Expired<JournalEntry<Event>> e -> handleExpired();
      case NextResult.Deleted<JournalEntry<Event>> d -> handleDeleted();
      case NextResult.Errored<JournalEntry<Event>>(var cause) -> handleError(cause);
    }
  }
}

// After — callback
try (CallbackSubscription sub = journal.subscribe(
    entry -> process(entry.data()),
    b -> b
        .onCompletion(() -> log.info("journal drained"))
        .onExpiration(() -> log.warn("journal expired"))
)) {
  waitUntilShutdown();
}
```

The consumer-escape-hatch test from spec 024 (consumer stuck on an
abandoned journal) must be rewritten to verify the new `subscribe` +
`next` pattern produces the same escape behavior — the feeder detects
inactivity-TTL expiry and marks the handoff expired, the consumer's
next `next()` call returns `NextResult.Expired`, and the consumer
cleanly exits its loop.

## Acceptance criteria

### `Journal<T>` interface

- [ ] `Journal.read()`, `Journal.readAfter(String)`, and
      `Journal.readLast(int)` no longer exist.
- [ ] `Journal.subscribe()`, `Journal.subscribeAfter(String)`, and
      `Journal.subscribeLast(int)` exist, returning
      `BlockingSubscription<JournalEntry<T>>`.
- [ ] Six callback subscribe overloads exist (two per starting
      position), returning `CallbackSubscription`.
- [ ] Javadoc on each subscribe method documents the starting-
      position semantics clearly.

### `JournalCursor<T>` deletion

- [ ] `org.jwcarman.substrate.journal.JournalCursor` no longer
      exists in `substrate-api`.
- [ ] No code anywhere in the repo references `JournalCursor`.
      Verified by grep.
- [ ] `org.jwcarman.substrate.core.journal.DefaultJournalCursor`
      no longer exists in `substrate-core`.

### `DefaultJournal<T>` implementation

- [ ] `DefaultJournal.read()`, `readAfter(String)`, `readLast(int)`
      methods are deleted.
- [ ] `DefaultJournal` constructor takes the subscription queue
      capacity from `SubstrateProperties.JournalProperties.subscription()`.
- [ ] Each `subscribe` method constructs a fresh
      `BlockingBoundedHandoff<JournalEntry<T>>` with that capacity.
- [ ] Each `subscribe` method spawns a dedicated feeder virtual
      thread via `Thread.ofVirtual().name("substrate-journal-feeder", 0).start(...)`.
- [ ] The feeder thread maintains a monotonic checkpoint in an
      `AtomicReference<String>` and advances it ONLY after a
      successful `handoff.push` (not before).
- [ ] The feeder thread subscribes to the notifier for the
      journal's key; handler releases the feeder's semaphore and
      marks the handoff deleted on `"__DELETED__"` payload.
- [ ] The feeder thread uses `semaphore.tryAcquire(1, SECONDS)` +
      `semaphore.drainPermits()` for notification coalescing.
- [ ] The feeder thread detects natural completion via
      `journalSpi.isComplete(key)` and performs a final drain
      (reading any entries appended between the previous batch and
      the completion marker) before calling `handoff.markCompleted()`.
- [ ] The feeder thread catches `JournalExpiredException` and
      calls `handoff.markExpired()`.
- [ ] The feeder thread catches other `RuntimeException` and calls
      `handoff.error(cause)`.
- [ ] The canceller closure interrupts the feeder thread, cancels
      the notifier subscription, and flips the running flag.
- [ ] `DefaultJournal.delete()` publishes a notification with
      payload `"__DELETED__"` after the SPI delete succeeds.

### Behavior — starting positions

- [ ] `subscribe()` delivers only entries appended after the
      subscription call, not historical entries. Verified by a test
      that appends 5 entries, calls subscribe, asserts no entries
      are delivered until a 6th append, then asserts only the 6th
      is delivered.
- [ ] `subscribeAfter(id)` delivers every entry with an id greater
      than the given id, including all existing entries and all
      future appends, in order.
- [ ] `subscribeLast(N)` delivers the last N retained entries in
      order, then continues with new appends. Verified by a test
      that appends 10 entries, calls `subscribeLast(3)`, and
      asserts the first 3 `next()` calls return entries #8, #9,
      #10 in that order.
- [ ] `subscribeLast(N)` where N is larger than the number of
      retained entries delivers all existing entries (not N,
      obviously). Verified by a test with a journal that has 2
      entries and `subscribeLast(100)` returning those 2.

### Behavior — checkpoint advancement

- [ ] The feeder's checkpoint advances monotonically and is updated
      ONLY after `handoff.push` returns successfully. Verified by a
      test that uses a capacity-1 handoff, appends 3 entries, pulls
      once, asserts the first entry was delivered, pulls again,
      asserts the second entry was delivered — the feeder must have
      advanced its checkpoint between pushes even while blocked on
      the bounded queue.
- [ ] If the feeder is blocked on `handoff.push` (backpressure)
      when a notification fires, the notification's permit is
      accumulated in the semaphore and is drained after the push
      completes. The feeder continues reading from its (unadvanced
      at the moment of blocking) checkpoint on the next iteration.

### Behavior — completion

- [ ] After `journal.complete(ttl)` is called, any subscription on
      the journal eventually delivers `NextResult.Completed` after
      all retained entries have been drained.
- [ ] The feeder's final drain (after seeing `isComplete == true`)
      catches entries appended concurrently with the completion
      marker. Verified by a test that starts a subscription,
      appends 5 entries, calls complete, asserts all 5 are
      delivered followed by Completed.

### Behavior — expiration and deletion

- [ ] Letting a journal's inactivity TTL elapse causes all active
      subscriptions to receive `NextResult.Expired` within one
      feeder poll interval. Verified with Awaitility.
- [ ] Calling `journal.delete()` causes all active subscriptions
      to receive `NextResult.Deleted` promptly (on the next
      semaphore wake-up, typically within milliseconds).
- [ ] Letting a completed journal's retention TTL elapse causes
      any remaining subscriptions to receive `NextResult.Expired`
      (not `Completed`, because the journal is gone now, not just
      completed).

### Backpressure

- [ ] A test verifies that the bounded handoff back-pressures the
      feeder. With a capacity-2 handoff, append 10 entries, pull
      one, assert only 3 entries are resident in the handoff
      (2 + 1 in flight from the feeder's current push attempt),
      then continue pulling and assert all 10 eventually arrive in
      order.

### Configuration

- [ ] Overriding `substrate.journal.subscription.queue-capacity:
      256` in `application.yml` results in `DefaultJournal`
      constructing handoffs with capacity 256. Verified by an
      autoconfiguration test.
- [ ] The default is 1024 (matching spec 033's
      `SubscriptionProperties` default).

### Test migration

- [ ] Every existing test that called `journal.read*(...)` or
      used `JournalCursor<T>` has been rewritten to use the new
      subscription API.
- [ ] No remaining references to `JournalCursor` in the test code.
      Verified by grep.
- [ ] The consumer-escape-hatch test from spec 024 is rewritten to
      use the new pattern and still passes.
- [ ] All migrated tests use Awaitility for time-sensitive
      assertions; no `Thread.sleep` additions.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers on every modified file.
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] **Atom and Mailbox are not modified by this spec.** Verified
      by `git diff --name-only` asserting no files under
      `org.jwcarman.substrate.core.atom` or
      `org.jwcarman.substrate.core.mailbox` have been touched.
- [ ] Backend modules implementing `JournalSpi` are not modified.

## Implementation notes

- The feeder thread's checkpoint semantics are the most critical
  correctness property in this spec. The checkpoint must advance
  ONLY after a successful `handoff.push` — never before, and never
  optimistically "push everything in batch, then update checkpoint
  once at the end." If the feeder crashes or is cancelled mid-batch,
  the checkpoint should reflect exactly the last entry actually
  delivered, not one beyond.
- The `handoff.push` call is the backpressure point. When the
  consumer is slow and the handoff queue fills, `push` blocks. The
  feeder is parked on that `push` call — no CPU, no notifier
  reads, no SPI load. When the consumer drains a slot, the feeder
  resumes. This is the only backpressure mechanism needed.
- The "final drain" after detecting completion is a subtle
  correctness concern: between the feeder's previous read and the
  moment it checks `isComplete`, a producer might have appended
  more entries AND called `complete`. Without the final drain,
  those entries would be lost. With the final drain, they're
  delivered before `markCompleted`.
- The `subscribeLast(count)` case uses the SPI's existing
  `readLast(key, count)` method (which `DefaultJournalCursor` was
  already using in spec 017). The feeder pushes those preloaded
  entries before entering its main loop, and then the main loop
  continues from the last preloaded entry's id as the starting
  checkpoint. This gives an atomic "historical + live" view: no
  duplicate deliveries, no gap between historical and live.
- **Never call `handoff.push` from inside the notifier handler.**
  The handler is synchronous and shared across all subscribers;
  calling push from there would block the notifier's dispatch and
  potentially deadlock. Push only happens on the feeder thread's
  own code path, protected by its own mind-state (the semaphore
  wait + loop).
- The feeder thread is one virtual thread per subscription. A
  deployment with 1,000 concurrent journal subscribers runs 1,000
  feeder virtual threads, each using ~2 KB of stack. That's well
  within Java 21+ virtual thread capacity.
- The existing spec 017 pattern for `DefaultJournalCursor`
  (reader virtual thread, bounded queue, semaphore nudging,
  drainPermits coalescing) maps directly onto the new feeder +
  `BlockingBoundedHandoff` pattern. The code in this spec is a
  refactoring of that existing machinery, not a new concurrency
  design.
