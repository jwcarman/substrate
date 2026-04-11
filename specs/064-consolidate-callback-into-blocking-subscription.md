# Consolidate DefaultCallbackSubscription into DefaultBlockingSubscription via a start() method

## What to build

Delete `DefaultCallbackSubscription<T>` entirely and fold its pump
behavior into `DefaultBlockingSubscription<T>` as a new
`start(Subscriber<T>)` method. The blocking subscription already owns
all the lifecycle state (the `done` flag, the canceller, the
shutdown-coordinator registration, the handoff wakeup). Adding a
pump thread is a natural extension — the current
`DefaultCallbackSubscription` is ~100 lines of pass-through wrapper
that delegates `isActive()` / `cancel()` to its inner blocking source
and adds exactly one thing: a background virtual thread looping over
`next(...)` and dispatching `NextResult` variants to a `Subscriber<T>`.

Two wins drive this change:

1. **No more `this`-escape in a constructor.** Today
   `DefaultCallbackSubscription`'s constructor stores `source` and
   then immediately calls `Thread.ofVirtual().start(...)`, handing
   the still-constructing `this` to a new thread. It's technically
   safe (the only captured field is `source`, assigned before
   `start()`, covered by `Thread.start()`'s happens-before) but it's
   fragile — a future field added to the class and read from the
   pump loop could silently break visibility. With the consolidation,
   the caller constructs the subscription normally and *then* calls
   `start(subscriber)`. The `this`-escape goes away.
2. **One class to reason about and test.** The existing
   `DefaultCallbackSubscriptionTest` folds into
   `DefaultBlockingSubscriptionTest` (or a new peer test class for
   pump-mode coverage). Stack traces lose one frame of indirection.

### Shape

```java
public class DefaultBlockingSubscription<T> implements BlockingSubscription<T> {

  private static final Duration MAX_POLL_DURATION = Duration.ofDays(365);
  private static final Log log = LogFactory.getLog(DefaultBlockingSubscription.class);

  private final NextHandoff<T> handoff;
  private final Runnable canceller;
  private final ShutdownCoordinator shutdownCoordinator;
  private final AtomicBoolean done = new AtomicBoolean(false);

  // null until start() is called; never cleared.
  private volatile Thread pumpThread;

  // ... existing constructors, next(), isActive() unchanged ...

  /**
   * Starts a background virtual thread that pumps values from this subscription
   * into {@code subscriber}. Returns {@code this} widened to {@link Subscription}
   * so push-style callers cannot accidentally reach {@link #next(Duration)}.
   *
   * <p>Call at most once per instance. After calling {@code start}, do not call
   * {@link #next(Duration)} — the pump thread owns that call.
   *
   * <p>If the subscription has already been cancelled (e.g. by the shutdown
   * coordinator racing with construction), {@code start} fires
   * {@link Subscriber#onCancelled()} synchronously on the calling thread and
   * returns without spawning a pump thread.
   */
  public synchronized Subscription start(Subscriber<T> subscriber) {
    if (done.get()) {
      safeRun(subscriber::onCancelled, "onCancelled");
      return this;
    }
    this.pumpThread = Thread.ofVirtual()
        .name("substrate-callback-handler", 0)
        .start(() -> pumpLoop(subscriber));
    return this;
  }

  @Override
  public synchronized void cancel() {
    if (markDone()) {
      canceller.run();
      handoff.markCancelled();
      Thread t = pumpThread;
      if (t != null) t.interrupt();
    }
  }

  private void pumpLoop(Subscriber<T> subscriber) {
    while (isActive()) {
      NextResult<T> result = next(MAX_POLL_DURATION);
      dispatch(result, subscriber);
      if (Thread.currentThread().isInterrupted()) return;
    }
  }

  private void dispatch(NextResult<T> result, Subscriber<T> subscriber) {
    switch (result) {
      case NextResult.Value<T>(T value) -> safeOnNext(subscriber, value);
      case NextResult.Timeout<T> _ -> {
        /* re-check loop condition */
      }
      case NextResult.Completed<T> _ -> safeRun(subscriber::onCompleted, "onCompleted");
      case NextResult.Expired<T> _ -> safeRun(subscriber::onExpired, "onExpired");
      case NextResult.Deleted<T> _ -> safeRun(subscriber::onDeleted, "onDeleted");
      case NextResult.Cancelled<T> _ -> safeRun(subscriber::onCancelled, "onCancelled");
      case NextResult.Errored<T>(Throwable cause) ->
          safeRun(() -> subscriber.onError(cause), "onError");
    }
  }

  private static <T> void safeOnNext(Subscriber<T> subscriber, T value) {
    try { subscriber.onNext(value); }
    catch (RuntimeException e) { log.warn("onNext handler threw", e); }
  }

  private static void safeRun(Runnable action, String label) {
    try { action.run(); }
    catch (RuntimeException e) { log.warn(label + " handler threw", e); }
  }
}
```

### Concurrency reasoning

- **`pumpThread` is `volatile`** so `cancel()` on an arbitrary thread
  sees whatever `start()` wrote.
- **`start()` and `cancel()` are `synchronized`** on `this` to
  serialize the shutdown race: if the shutdown coordinator calls
  `cancel()` between the constructor and `start()`, one of two things
  happens:
  - `cancel()` wins the monitor: it flips `done`, calls the canceller,
    wakes the handoff, and exits (no pump thread to interrupt).
    `start()` then sees `done == true` inside the `synchronized`
    block, fires `onCancelled` synchronously, and returns without
    spawning a thread.
  - `start()` wins the monitor: it spawns the pump thread, then
    `cancel()` runs, flips `done`, calls the canceller, wakes the
    handoff (which makes the pump's next `next()` call return
    `Cancelled`), and interrupts the pump thread. The pump dispatches
    `onCancelled` once and exits.
- **`next()` itself is not synchronized.** The `synchronized` block
  only protects the start/cancel state transition — holding the
  monitor across a blocking `next()` call would deadlock cancel.
  The existing `markDone()` / `done.compareAndSet` pattern inside
  `next()` is unchanged.
- **The pump thread only reads `this.handoff`, `this.done` (via
  `isActive()`), and its own interrupt flag.** All three are safely
  published by the `synchronized` block in `start()` before the
  virtual thread runs.

### Public API impact

The public `Subscription subscribe(Subscriber<T> subscriber)` methods
on Atom / Journal / Mailbox stay exactly the same from the caller's
perspective. Only the internal wiring changes:

```java
// DefaultAtom.java — before
private Subscription buildCallbackSubscription(
    Snapshot<T> lastSeen, Subscriber<Snapshot<T>> subscriber) {
  CoalescingHandoff<Snapshot<T>> handoff = new CoalescingHandoff<>();
  Runnable canceller = startFeeder(handoff, lastSeen);
  DefaultBlockingSubscription<Snapshot<T>> source =
      new DefaultBlockingSubscription<>(handoff, canceller, shutdownCoordinator);
  return new DefaultCallbackSubscription<>(source, subscriber);
}

// DefaultAtom.java — after
private Subscription buildCallbackSubscription(
    Snapshot<T> lastSeen, Subscriber<Snapshot<T>> subscriber) {
  CoalescingHandoff<Snapshot<T>> handoff = new CoalescingHandoff<>();
  Runnable canceller = startFeeder(handoff, lastSeen);
  return new DefaultBlockingSubscription<>(handoff, canceller, shutdownCoordinator)
      .start(subscriber);
}
```

Same pattern for `DefaultJournal` and `DefaultMailbox`.

### Test migration

- Delete `DefaultCallbackSubscriptionTest`.
- Create a new test class (suggested name:
  `DefaultBlockingSubscriptionPumpTest` alongside the existing
  `DefaultBlockingSubscriptionTest`, or fold the cases into the
  existing test class) that reproduces every scenario the old
  `DefaultCallbackSubscriptionTest` exercised, but targets
  `DefaultBlockingSubscription.start(subscriber)` directly.
- Keep the three anonymous `NextHandoff<String>` overrides that
  already implement `markCancelled()` — they still need that method.
- The pump loop's behavior (value delivery, terminal event dispatch,
  handler-exception isolation, cancel latency, idle no-op, external
  interrupt, interrupt-does-not-fire-onError, value-wakes-parked-
  handler) should all be preserved verbatim.

Add one new test that specifically covers the shutdown-race path:

```java
@Test
void startAfterCancelFiresOnCancelledSynchronouslyAndDoesNotSpawnPump() {
  // Build a subscription, call cancel() (or stop the coordinator),
  // then call start(subscriber). Assert that subscriber.onCancelled()
  // ran exactly once on the caller's thread, and that no pump thread
  // was spawned.
}
```

## Acceptance criteria

- [ ] `DefaultCallbackSubscription.java` is deleted from `substrate-core/src/main/java/.../subscription/`.
- [ ] `DefaultBlockingSubscription<T>` has a new public method `Subscription start(Subscriber<T> subscriber)` with the shape described above.
- [ ] `start()` and `cancel()` are both `synchronized`, and the class has a `volatile Thread pumpThread` field that starts `null`.
- [ ] Calling `start()` on an already-cancelled subscription invokes `subscriber.onCancelled()` synchronously (and exactly once) on the caller's thread and does not spawn a pump thread.
- [ ] `DefaultAtom`, `DefaultJournal`, `DefaultMailbox` build callback subscriptions via `new DefaultBlockingSubscription<>(...).start(subscriber)`. No references to `DefaultCallbackSubscription` remain in the three primitives.
- [ ] `DefaultCallbackSubscriptionTest` is deleted; its test cases are replaced with equivalent pump-mode coverage on `DefaultBlockingSubscription` (value delivery; each terminal event — `onCompleted`, `onExpired`, `onDeleted`, `onCancelled`, `onError`; handler-exception isolation for each terminal; `cancel()` stops the loop promptly; external interrupt exits without firing `onError`; idle subscription does no periodic work; value pushed while parked wakes the pump).
- [ ] New test: `start()` after `cancel()` fires `onCancelled` synchronously and does not spawn a thread.
- [ ] `grep -r "DefaultCallbackSubscription" src/main src/test` comes up empty across the whole repo.
- [ ] `./mvnw verify` passes from the root. `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.
- [ ] README / CHANGELOG do not need updating — the public API on Atom / Journal / Mailbox is unchanged.

## Implementation notes

- The existing `DefaultBlockingSubscription.cancel()` logic must be
  preserved: it still flips `done`, calls the feeder canceller, and
  wakes the handoff via `markCancelled()`. The consolidation only
  *adds* the `pumpThread.interrupt()` line inside the existing
  `markDone()` branch.
- `DefaultBlockingSubscription.next()` stays non-synchronized. The
  `synchronized` keyword is only on `start()` and `cancel()`. A
  thread blocked inside `next()` will *not* hold the monitor (monitor
  is released by Java when the method returns), so `cancel()` on
  another thread can always acquire it.

  Wait — `next()` is NOT synchronized, so it never acquires the
  monitor in the first place. The monitor is only held during the
  state-transition windows of `start()` and `cancel()`. The pump
  thread running `pumpLoop` (which calls `next()` directly) does not
  hold the monitor while blocked.
- The static `log` and `MAX_POLL_DURATION` constants move from
  `DefaultCallbackSubscription` into `DefaultBlockingSubscription`.
- Do NOT break the pull-mode API. Callers that use
  `subscribe()` → `BlockingSubscription<T>` (without ever calling
  `start()`) must still work exactly as before. The `pumpThread`
  field stays `null`, `cancel()`'s `if (t != null)` guard skips the
  interrupt, everything else is unchanged.
- Keep the helper static methods (`safeOnNext`, `safeRun`) private
  on `DefaultBlockingSubscription`. Do not move them to a utility
  class.
- The anonymous `NextHandoff<String>` subclasses in the old callback
  test file already override `markCancelled()` — port that style over
  verbatim when you move the test cases.

## Out of scope

- Changing the public `Subscription` / `BlockingSubscription` / `Subscriber` / `SubscriberConfig` interfaces. This spec is a pure internal refactor.
- Changing `ShutdownCoordinator`, `NextHandoff`, or any handoff impl.
- Coverage / Sonar cleanup (covered by specs in `specs/backlog/`).
