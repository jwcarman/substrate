# Atom subscription migration

**Depends on: spec 033 (subscription foundation) must be completed
first.** This spec migrates Atom from the existing `watch` method to
the new `Subscription`-based model built on `CoalescingHandoff`.

## What to build

Replace `Atom.watch(Snapshot<T>, Duration)` with the new
subscription model:

- Add four `subscribe` methods to the `Atom<T>` interface (two
  blocking variants + four callback variants)
- Remove `Atom.watch(Snapshot<T>, Duration)`
- Rewrite `DefaultAtom<T>` to construct a `CoalescingHandoff<>` and
  spin up a feeder virtual thread for each subscription
- Publish a `"__DELETED__"` notification payload on `delete()` so
  subscribers detect explicit deletion immediately
- Migrate every call site that used `atom.watch(...)` to the new
  API

Scope is strictly Atom. Journal and Mailbox keep their existing
consumer-side APIs (`Journal.read*` cursors, `Mailbox.poll`). Specs
035 and 036 migrate those.

## `Atom<T>` interface changes

```java
package org.jwcarman.substrate.atom;

import java.time.Duration;
import java.util.function.Consumer;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;

public interface Atom<T> {

  // ═══════════════ writes (unchanged) ═══════════════
  void set(T data, Duration ttl);
  boolean touch(Duration ttl);
  void delete();

  // ═══════════════ synchronous read (unchanged) ═══════════════
  Snapshot<T> get();

  // ═══════════════ blocking subscribe (NEW) ═══════════════

  /**
   * Subscribe from the current state. The first {@code next()} call
   * returns the current snapshot (if the atom exists); subsequent
   * {@code next()} calls block for the next {@code set()}.
   */
  BlockingSubscription<Snapshot<T>> subscribe();

  /**
   * Subscribe from a known baseline. If the atom's current token
   * differs from {@code lastSeen.token()}, the first {@code next()}
   * call returns the current snapshot; otherwise the first
   * {@code next()} blocks for the next change.
   *
   * @param lastSeen the baseline to compare against, or null for
   *                 equivalent behavior to {@link #subscribe()}
   */
  BlockingSubscription<Snapshot<T>> subscribe(Snapshot<T> lastSeen);

  // ═══════════════ callback subscribe (NEW) ═══════════════

  /** Callback subscribe with only an onNext handler, from current state. */
  CallbackSubscription subscribe(Consumer<Snapshot<T>> onNext);

  /** Callback subscribe with onNext and additional lifecycle handlers. */
  CallbackSubscription subscribe(
      Consumer<Snapshot<T>> onNext,
      Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer);

  /** Callback subscribe from a known baseline, with only onNext. */
  CallbackSubscription subscribe(
      Snapshot<T> lastSeen,
      Consumer<Snapshot<T>> onNext);

  /** Callback subscribe from a known baseline with additional handlers. */
  CallbackSubscription subscribe(
      Snapshot<T> lastSeen,
      Consumer<Snapshot<T>> onNext,
      Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer);

  // ═══════════════ identification (unchanged) ═══════════════
  String key();
}
```

### Removed

- `Optional<Snapshot<T>> watch(Snapshot<T> lastSeen, Duration timeout)`

Every call site migrates to:

```java
// Before
Optional<Snapshot<Session>> fresh = atom.watch(current, Duration.ofSeconds(30));
if (fresh.isPresent()) {
  current = fresh.get();
  process(current);
}

// After — blocking
try (BlockingSubscription<Snapshot<Session>> sub = atom.subscribe(current)) {
  while (sub.isActive()) {
    switch (sub.next(Duration.ofSeconds(30))) {
      case NextResult.Value<Snapshot<Session>>(var snap) -> {
        current = snap;
        process(snap);
      }
      case NextResult.Timeout<Snapshot<Session>> t -> {}
      case NextResult.Expired<Snapshot<Session>> e -> handleExpired();
      case NextResult.Deleted<Snapshot<Session>> d -> handleDeleted();
      case NextResult.Completed<Snapshot<Session>> c -> {}  // never for Atom
      case NextResult.Errored<Snapshot<Session>>(var cause) -> handleError(cause);
    }
  }
}

// After — callback
try (CallbackSubscription sub = atom.subscribe(
    current,
    snap -> process(snap),
    b -> b.onExpiration(this::handleExpired).onDelete(this::handleDeleted)
)) {
  waitUntilShutdown();
}
```

## `DefaultAtom<T>` rewrite

Replace the existing `watch` implementation with `subscribe` methods
that construct a `CoalescingHandoff`, spin up a feeder virtual
thread, and return the appropriate subscription type.

### Atom feeder thread

The feeder reads the atom from the SPI, compares the current token
to the last-known token, pushes new snapshots into the handoff, and
watches for notifications including the `"__DELETED__"` marker:

```java
// In DefaultAtom<T>

private BlockingSubscription<Snapshot<T>> buildBlockingSubscription(Snapshot<T> lastSeen) {
  CoalescingHandoff<Snapshot<T>> handoff = new CoalescingHandoff<>();
  Runnable canceller = startFeeder(handoff, lastSeen);
  return new DefaultBlockingSubscription<>(handoff, canceller);
}

private CallbackSubscription buildCallbackSubscription(
    Snapshot<T> lastSeen,
    Consumer<Snapshot<T>> onNext,
    Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer) {
  CoalescingHandoff<Snapshot<T>> handoff = new CoalescingHandoff<>();
  Runnable canceller = startFeeder(handoff, lastSeen);

  DefaultCallbackSubscriberBuilder<Snapshot<T>> builder =
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

private Runnable startFeeder(CoalescingHandoff<Snapshot<T>> handoff, Snapshot<T> lastSeen) {
  AtomicBoolean running = new AtomicBoolean(true);
  Semaphore semaphore = new Semaphore(0);
  AtomicReference<String> lastToken = new AtomicReference<>(
      lastSeen != null ? lastSeen.token() : null);

  NotifierSubscription notifierSub = notifier.subscribe((notifiedKey, payload) -> {
    if (!key.equals(notifiedKey)) return;
    if ("__DELETED__".equals(payload)) {
      handoff.markDeleted();
      running.set(false);
      semaphore.release();
    } else {
      semaphore.release();
    }
  });

  Thread feederThread = Thread.ofVirtual()
      .name("substrate-atom-feeder", 0)
      .start(() -> {
        try {
          while (running.get() && !Thread.currentThread().isInterrupted()) {
            Optional<AtomRecord> record = atomSpi.read(key);
            if (record.isEmpty()) {
              handoff.markExpired();
              return;
            }
            if (!record.get().token().equals(lastToken.get())) {
              Snapshot<T> snap = new Snapshot<>(
                  codec.decode(record.get().value()),
                  record.get().token());
              handoff.push(snap);
              lastToken.set(snap.token());
            }
            semaphore.tryAcquire(1, TimeUnit.SECONDS);
            semaphore.drainPermits();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
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
```

### `subscribe(...)` methods

Each public subscribe method delegates to the two internal helpers:

```java
@Override
public BlockingSubscription<Snapshot<T>> subscribe() {
  return buildBlockingSubscription(null);
}

@Override
public BlockingSubscription<Snapshot<T>> subscribe(Snapshot<T> lastSeen) {
  return buildBlockingSubscription(lastSeen);
}

@Override
public CallbackSubscription subscribe(Consumer<Snapshot<T>> onNext) {
  return buildCallbackSubscription(null, onNext, null);
}

@Override
public CallbackSubscription subscribe(
    Consumer<Snapshot<T>> onNext,
    Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer) {
  return buildCallbackSubscription(null, onNext, customizer);
}

@Override
public CallbackSubscription subscribe(Snapshot<T> lastSeen, Consumer<Snapshot<T>> onNext) {
  return buildCallbackSubscription(lastSeen, onNext, null);
}

@Override
public CallbackSubscription subscribe(
    Snapshot<T> lastSeen,
    Consumer<Snapshot<T>> onNext,
    Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer) {
  return buildCallbackSubscription(lastSeen, onNext, customizer);
}
```

### `delete()` publishes `"__DELETED__"`

```java
@Override
public void delete() {
  atomSpi.delete(key);
  notifier.notify(key, "__DELETED__");
}
```

Subscribers' feeder threads watch for this payload and call
`handoff.markDeleted()` immediately, so consumers see
`NextResult.Deleted` without waiting for the next poll interval.

### Removed

- `watch(Snapshot<T> lastSeen, Duration timeout)` — delete the method
  and its implementation from `DefaultAtom`.

## Test migration

Every existing test that used `atom.watch(...)` needs a rewrite. The
pattern:

```java
// Before
Optional<Snapshot<Session>> fresh = atom.watch(current, Duration.ofSeconds(30));
assertThat(fresh).isPresent();

// After
try (BlockingSubscription<Snapshot<Session>> sub = atom.subscribe(current)) {
  NextResult<Snapshot<Session>> result = sub.next(Duration.ofSeconds(30));
  assertThat(result).isInstanceOf(NextResult.Value.class);
  NextResult.Value<Snapshot<Session>> value = (NextResult.Value<Snapshot<Session>>) result;
  // assertions on value.value()
}
```

For callback-style tests, use a `CountDownLatch` or `AtomicReference`
to capture the invocation:

```java
AtomicReference<Snapshot<Session>> captured = new AtomicReference<>();
CountDownLatch invoked = new CountDownLatch(1);
try (CallbackSubscription sub = atom.subscribe(snap -> {
    captured.set(snap);
    invoked.countDown();
})) {
  atom.set(newSession, SESSION_TTL);
  assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
  assertThat(captured.get().value()).isEqualTo(newSession);
}
```

All Awaitility patterns in existing tests should be preserved — no
`Thread.sleep` additions.

## Acceptance criteria

### `Atom<T>` interface

- [ ] `Atom.watch(Snapshot<T>, Duration)` no longer exists on the
      interface.
- [ ] `Atom.subscribe()` and `Atom.subscribe(Snapshot<T>)` exist,
      returning `BlockingSubscription<Snapshot<T>>`.
- [ ] The four callback `subscribe` overloads exist, returning
      `CallbackSubscription`.
- [ ] Javadoc on each subscribe method clearly describes the initial
      emission semantics (current value delivered if lastSeen is
      null or token differs; block otherwise).

### `DefaultAtom<T>` implementation

- [ ] `DefaultAtom.watch(...)` method is deleted.
- [ ] `DefaultAtom.subscribe(...)` methods construct a fresh
      `CoalescingHandoff<Snapshot<T>>` per invocation.
- [ ] Each subscribe method spawns a dedicated feeder virtual thread
      (via `Thread.ofVirtual().name("substrate-atom-feeder", 0).start(...)`).
- [ ] The feeder thread subscribes to the `NotifierSpi` for the
      atom's key and calls `handoff.markDeleted()` on receiving a
      `"__DELETED__"` notification payload.
- [ ] The feeder thread does an initial "just in case" read and
      pushes the current snapshot if the token differs from
      `lastSeen.token()`.
- [ ] The feeder thread detects atom expiry (SPI read returns
      `Optional.empty()`) and calls `handoff.markExpired()`.
- [ ] The feeder thread catches unexpected `RuntimeException` and
      calls `handoff.error(cause)`.
- [ ] The canceller closure interrupts the feeder thread, cancels
      the notifier subscription, and flips the running flag to
      false.
- [ ] `DefaultAtom.delete()` publishes a notification with payload
      `"__DELETED__"` after the SPI delete succeeds.

### Behavior

- [ ] A fresh `subscribe()` on a live atom delivers the current
      snapshot as the first `next()` result. Verified by a unit
      test.
- [ ] A `subscribe(lastSeen)` where `lastSeen.token()` matches the
      current atom token blocks on the first `next()` until a new
      `set` occurs. Verified by a unit test.
- [ ] Calling `atom.set(v, ttl)` from another thread while a
      subscription is active causes the subscriber to receive a
      `NextResult.Value` for the new snapshot.
- [ ] Calling `atom.delete()` causes all active subscribers to
      receive `NextResult.Deleted` promptly (within a few hundred
      milliseconds, verified with Awaitility).
- [ ] Letting an atom's TTL elapse causes all active subscribers to
      receive `NextResult.Expired` on the next feeder poll cycle
      (within ~1 second of expiry, verified with Awaitility).
- [ ] Rapid `set` calls in sequence — because Atom uses
      `CoalescingHandoff`, a slow subscriber sees only the LATEST
      snapshot, not every intermediate value. Verified by a test
      that calls `set(v1)`, `set(v2)`, `set(v3)` in rapid
      succession, then pulls once, and asserts the pull returns
      `Value(v3)` (not `Value(v1)`).
- [ ] `CallbackSubscription` variants fire `onNext` for each new
      snapshot and fire `onExpiration`/`onDelete`/`onError` on the
      matching terminal state.
- [ ] `Atom.onChange` (callback subscribe) never fires
      `onComplete` — atoms have no natural completion. Verified by
      a test that waits 500ms with no changes and asserts the
      onComplete handler (if registered) was not invoked.

### Test migration

- [ ] Every existing test that called `atom.watch(...)` has been
      rewritten to use the new subscription API.
- [ ] No remaining references to `atom.watch` anywhere in the
      repo, verified by grep.
- [ ] All migrated tests use Awaitility for time-sensitive
      assertions; no `Thread.sleep` additions.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers on every modified file (unchanged
      from existing).
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] **Journal and Mailbox are not modified by this spec.**
      Verified by running `git diff --name-only` and asserting that
      no files under `org.jwcarman.substrate.core.journal` or
      `org.jwcarman.substrate.core.mailbox` have been touched.
- [ ] **Backend modules are not modified.** Atom-related backend
      modules (`substrate-redis/atom`, etc.) still work with the new
      `DefaultAtom` — they don't interact with the subscription
      machinery at all; they implement `AtomSpi` which is unchanged.

## Implementation notes

- The feeder thread per subscription is lightweight — ~2 KB of
  virtual thread memory. A deployment with 10,000 concurrent atom
  subscribers runs 10,000 virtual threads, which is well within
  Java 21+ capacity.
- `CoalescingHandoff` makes atoms resilient to slow subscribers.
  `atom.set()` never blocks on backpressure (the handoff's `push`
  is non-blocking for coalescing), so rapid producers don't slow
  down regardless of how many subscribers are watching.
- The feeder thread's "just in case" initial read handles the race
  where a `set` happens between the notifier subscription being
  registered and the feeder loop starting — the initial read
  catches any state that was already there.
- The feeder's 1-second semaphore poll interval is a reasonable
  upper bound on "worst-case latency to detect TTL expiry" — the
  feeder wakes up at least once per second and re-reads the SPI.
  If a notifier event fires in between, the wake-up is immediate.
- The `__DELETED__` notification payload is a convention — the
  string constant lives in `DefaultAtom` and is checked by the
  feeder's notifier handler. If the payload scheme ever grows
  beyond plain string constants (structured notifications,
  versioned payloads), refactor the payload check into a shared
  utility. Not needed for the first cut.
- `Atom.get()` (the synchronous read) does NOT go through the
  subscription machinery. It's an independent SPI call that returns
  the current snapshot directly. Subscriptions are for "notify me
  when it changes"; get is for "what is it right now."
