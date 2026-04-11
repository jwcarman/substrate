# Subscription interrupt handling + `NextResult.isTerminal` cleanup + idle-wake elimination

**Depends on: spec 033 (subscription foundation) must be completed
first.** This spec follows up on spec 033 with three small fixes that
were identified after 033 was already being implemented.

## What to build

Five related cleanup changes to the subscription machinery from
spec 033 and the feeder implementations from specs 034 and 035:

1. **Fix an interrupt-loop bug** in
   `DefaultBlockingSubscription.next(Duration)` and
   `DefaultCallbackSubscription`'s handler loop. Without the fix, a
   consumer thread that gets interrupted during a `pull` call can
   end up in an infinite loop of `NextResult.Timeout` results rather
   than exiting cleanly.

2. **Clean up the awkward switch pattern** in
   `DefaultBlockingSubscription.next` by adding a `default boolean
   isTerminal()` method to the `NextResult<T>` sealed interface.
   Terminal variants return true; `Value` and `Timeout` override to
   return false. The caller code becomes a single `if` statement
   instead of a switch with two empty cases.

3. **Eliminate the periodic 1-second wake-up** in
   `DefaultCallbackSubscription`'s handler loop. Instead of polling
   with a 1-second timeout and checking `done` on each iteration,
   the handler loop parks inside `handoff.pull` for effectively
   forever (using `Long.MAX_VALUE` milliseconds as the pull timeout)
   and relies on `cancel()` interrupting the handler thread to wake
   it up promptly. Idle subscriptions do zero periodic work; cancel
   latency drops from "up to 1 second" to "microseconds."

4. **Guard `semaphore.tryAcquire` with its return value** in the
   feeder threads of `DefaultAtom` (from spec 034) and
   `DefaultJournal` (from spec 035). The current code calls
   `tryAcquire` and then unconditionally calls `drainPermits`,
   ignoring the boolean return. The correct pattern — which spec
   017's original `DefaultJournalCursor` feeder used — is
   `if (semaphore.tryAcquire(...)) { semaphore.drainPermits(); }`.
   The behavior difference is that `drainPermits` is only called
   when `tryAcquire` actually acquired a permit (meaning at least
   one notification fired); when `tryAcquire` times out, there's
   nothing stacked to drain.

5. **Rename the `record` local variable in `DefaultAtom`** (two
   occurrences: inside `get()` and inside the feeder loop). `record`
   is a Java restricted identifier — it compiles as a variable name
   but it's confusing because readers expect it to be a type
   declaration. Rename to `raw` (or `rawAtom`) to avoid the
   cognitive stumble.

Changes 1, 2, and 3 touch only `substrate-api` and
`substrate-core/.../subscription`. Changes 4 and 5 touch already-
committed code in `substrate-core/.../atom/DefaultAtom.java` and
`substrate-core/.../journal/DefaultJournal.java`. Zero impact on
primitive interfaces, backends, or tests outside the subscription
and feeder layers.

## The bug: interrupted consumers spin on Timeout forever

Scenario:

1. Consumer thread is interrupted (Spring shutdown, test cleanup,
   explicit `Thread.interrupt()`)
2. Consumer loop calls `sub.next(Duration.ofSeconds(30))`
3. `next` calls `handoff.pull(timeout)`
4. `pull` calls `LinkedBlockingQueue.poll(timeout, unit)`
5. Internally, `poll` calls `ReentrantLock.lockInterruptibly()`,
   which calls `Thread.interrupted()` — this **clears the flag**
   and returns true, then throws `InterruptedException`
6. `pull`'s catch block calls `Thread.currentThread().interrupt()`
   to restore the flag (correct Java idiom) and returns
   `NextResult.Timeout`
7. `next` returns `Timeout` to the caller
8. Caller's loop pattern-matches `Timeout` as "keep waiting" and
   loops back
9. `sub.isActive()` returns true (no terminal state set)
10. Consumer calls `sub.next(timeout)` again
11. **Same path** — flag is still set from step 6, `poll` clears
    and throws again, catch restores, returns Timeout
12. **Infinite loop of fast-returning Timeouts** — the caller never
    exits because no terminal state was signaled

The root cause is that `Thread.interrupted()` clears the flag on
each call, and our catch block re-sets it. So the flag toggles on
every iteration, and `LinkedBlockingQueue.poll` throws immediately
on each call without ever actually blocking. The consumer's loop
spins at CPU speed returning Timeout forever.

### Why the current Java idiom is still correct

The pattern `catch (InterruptedException e) { Thread.currentThread().interrupt(); return timeoutValue; }`
IS the canonical Java idiom for "I can't propagate checked
exceptions but I want to preserve the interrupt signal for
upstream." It's correct at the handoff layer. The bug is at the
**subscription layer**, where no one is translating "the pull was
interrupted" into "the subscription should exit."

### The fix

`DefaultBlockingSubscription.next` needs to check the interrupt
flag after `pull` returns. If the flag is set, the subscription
should flip `done` so the caller's `while (sub.isActive())` loop
exits on the next iteration. The flag is preserved for upstream
handling — setting `done` is a subscription-level signal, not a
thread-level one.

Same issue in `DefaultCallbackSubscription`'s handler loop —
identical fix.

## The cleanup: `NextResult.isTerminal`

The current `DefaultBlockingSubscription.next` body, as drafted
in spec 033:

```java
@Override
public NextResult<T> next(Duration timeout) {
  NextResult<T> result = handoff.pull(timeout);
  switch (result) {
    case NextResult.Value<T> v -> {}
    case NextResult.Timeout<T> t -> {}
    default -> done.set(true);
  }
  return result;
}
```

That switch is awkward:

- Two empty cases with braces — they exist only to NOT match the
  default, which feels inverted
- The semantic being expressed — "flip done if result is terminal"
   — is buried in the absence of cases rather than spelled out
- `default` lumps four distinct terminal states together, which is
  technically fine but obscures the exhaustive nature of the sealed
  type
- Adding a new `NextResult` variant (e.g., a hypothetical `Cancelled`)
  would silently fall into `default` without forcing the author to
  think about it — the compiler's exhaustiveness check doesn't help
  because the switch isn't exhaustive as written

The fix: put the semantic on the sealed type where it belongs.

### `NextResult` with `isTerminal`

```java
package org.jwcarman.substrate;

public sealed interface NextResult<T>
    permits NextResult.Value,
            NextResult.Timeout,
            NextResult.Completed,
            NextResult.Expired,
            NextResult.Deleted,
            NextResult.Errored {

  /**
   * True if this result represents the end of the subscription —
   * no more values will arrive. {@code Completed}, {@code Expired},
   * {@code Deleted}, and {@code Errored} are all terminal.
   * {@code Value} and {@code Timeout} are not terminal — more
   * values may still arrive on subsequent pulls.
   */
  default boolean isTerminal() {
    return true;
  }

  record Value<T>(T value) implements NextResult<T> {
    @Override public boolean isTerminal() { return false; }
  }

  record Timeout<T>() implements NextResult<T> {
    @Override public boolean isTerminal() { return false; }
  }

  record Completed<T>() implements NextResult<T> {}    // inherits true
  record Expired<T>() implements NextResult<T> {}      // inherits true
  record Deleted<T>() implements NextResult<T> {}      // inherits true
  record Errored<T>(Throwable cause) implements NextResult<T> {}  // inherits true
}
```

The default is `true` (terminal), and only the two non-terminal
variants override. This minimizes boilerplate on the records that
are the actual terminal states, and it means adding a new terminal
variant in the future (if ever) will inherit the correct default
without the author needing to remember to add the method.

### `DefaultBlockingSubscription.next` after cleanup

```java
@Override
public NextResult<T> next(Duration timeout) {
  // If the caller's thread is already interrupted, don't even try
  // to pull — flip done and return Timeout so the caller's loop
  // exits via isActive() returning false.
  if (Thread.currentThread().isInterrupted()) {
    done.set(true);
    return new NextResult.Timeout<>();
  }

  NextResult<T> result = handoff.pull(timeout);

  // Terminal variants flip done.
  if (result.isTerminal()) {
    done.set(true);
  }

  // If the pull was interrupted and the handoff's catch block
  // restored the interrupt flag, we also flip done so a caller
  // looping on isActive() + next() exits rather than spinning on
  // Timeout forever.
  if (Thread.currentThread().isInterrupted()) {
    done.set(true);
  }

  return result;
}
```

Two small checks bracketing the `pull` call, plus one clean
`isTerminal()` predicate. No switch with empty cases.

### `DefaultCallbackSubscription` handler loop cleanup

The handler-loop switch is different from `next()`'s — each case
does primitive-specific work (invoke a specific callback), so the
switch body is meaningful and shouldn't be collapsed into a
predicate. But the handler loop has two issues to fix: the
interrupt bug (same as `next`) and the unnecessary 1-second
periodic wake-up.

**Pull for effectively forever.** Replace the 1-second
`HANDLER_POLL_INTERVAL` with a near-infinite duration. The handler
thread parks inside `handoff.pull` until one of three things
happens:

1. The feeder pushes a value → `LinkedBlockingQueue.put` signals
   its internal `notEmpty` condition, which wakes up the pull
   immediately. No added latency versus the 1-second version.
2. The feeder marks a terminal state → same wake-up path via the
   queue's condition.
3. The handler thread is interrupted (because `cancel()` called
   `handlerThread.interrupt()`) → the queue's `lockInterruptibly`
   throws, the handoff's catch block restores the flag and returns
   `NextResult.Timeout`, and the handler loop's top-of-iteration
   `done.get()` check sees `done = true` and exits.

**Track the handler thread reference** so `cancel()` can interrupt
it. Store the `Thread` returned from `Thread.ofVirtual().start(...)`
in a field.

**`cancel()` interrupts the handler thread.** Without this, the
handler would never notice `done = true` because it's parked
indefinitely inside the pull.

### Updated `DefaultCallbackSubscription`

```java
public class DefaultCallbackSubscription<T> implements CallbackSubscription {

  private static final Log log = LogFactory.getLog(DefaultCallbackSubscription.class);

  /**
   * Effectively forever — the handler loop parks inside
   * handoff.pull until it receives a value, a terminal marker,
   * or the handler thread is interrupted by cancel(). Using
   * Long.MAX_VALUE milliseconds (~292 million years) means the
   * underlying queue.poll never actually times out on its own.
   */
  private static final Duration MAX_POLL_DURATION =
      Duration.ofMillis(Long.MAX_VALUE);

  private final NextHandoff<T> handoff;
  private final Runnable canceller;
  private final AtomicBoolean done = new AtomicBoolean(false);
  private final Thread handlerThread;

  public DefaultCallbackSubscription(
      NextHandoff<T> handoff,
      Runnable canceller,
      Consumer<T> onNext,
      Consumer<Throwable> onError,
      Runnable onExpiration,
      Runnable onDelete,
      Runnable onComplete) {
    this.handoff = handoff;
    this.canceller = canceller;
    this.handlerThread = Thread.ofVirtual()
        .name("substrate-callback-handler", 0)
        .start(() -> runHandlerLoop(onNext, onError, onExpiration, onDelete, onComplete));
  }

  private void runHandlerLoop(
      Consumer<T> onNext,
      Consumer<Throwable> onError,
      Runnable onExpiration,
      Runnable onDelete,
      Runnable onComplete) {

    while (!done.get()) {
      // Top-of-iteration check handles "thread was interrupted
      // before or after pull, but before we looped back."
      if (Thread.currentThread().isInterrupted()) {
        done.set(true);
        return;
      }

      NextResult<T> result = handoff.pull(MAX_POLL_DURATION);

      switch (result) {
        case NextResult.Value<T>(T value) -> {
          try {
            onNext.accept(value);
          } catch (RuntimeException e) {
            log.warn("onNext handler threw", e);
          }
        }
        case NextResult.Timeout<T> t -> {
          // Normally unreachable with MAX_POLL_DURATION, but this
          // case also fires if the pull was interrupted (handoff
          // caught InterruptedException, restored the flag, and
          // returned Timeout). The next top-of-loop interrupt
          // check will exit the loop on the next iteration.
        }
        case NextResult.Completed<T> c -> {
          done.set(true);
          safeRun(onComplete);
        }
        case NextResult.Expired<T> e -> {
          done.set(true);
          safeRun(onExpiration);
        }
        case NextResult.Deleted<T> d -> {
          done.set(true);
          safeRun(onDelete);
        }
        case NextResult.Errored<T>(Throwable cause) -> {
          done.set(true);
          safeAccept(onError, cause);
        }
      }

      // Second check: if the pull was interrupted, exit now
      // instead of looping once more.
      if (Thread.currentThread().isInterrupted()) {
        done.set(true);
      }
    }
  }

  @Override
  public boolean isActive() {
    return !done.get();
  }

  @Override
  public void cancel() {
    done.set(true);
    handlerThread.interrupt();   // wake the handler loop from its pull
    canceller.run();               // tear down the feeder + notifier
  }

  // ... safeRun / safeAccept helpers unchanged from spec 033 ...
}
```

Together:

- **Idle subscriptions do zero periodic work.** The handler thread
  is parked inside `queue.poll(Long.MAX_VALUE, MILLISECONDS)`,
  consuming no CPU and triggering no wake-ups.
- **Cancel latency is microseconds**, not up to 1 second. The
  `handlerThread.interrupt()` call immediately unblocks the pull,
  the catch block restores the flag, pull returns Timeout, the
  loop sees `done = true` and exits.
- **Value and terminal delivery latency is unchanged.** The
  underlying `LinkedBlockingQueue` signals its internal condition
  as soon as a feeder pushes, so the handler thread wakes up just
  as fast as it did with the 1-second poll interval.
- **The interrupt-flag check logic from fix #1** still handles the
  "interrupted during pull" case. Without it, the handler would
  see Timeout from the interrupted pull and loop back to another
  pull, which would immediately throw InterruptedException again
  (infinite spin). With the top-of-loop and bottom-of-loop
  interrupt checks, the spin is broken.

### Why we keep the switch in the callback handler loop

Unlike `next()`, this switch isn't awkward — each case does
distinct work (invoke a specific callback) and no pair of cases
collapses into "same behavior." The switch is exhaustive over
the sealed type, so if a new `NextResult` variant is ever added,
the compiler forces us to handle it explicitly. That's the good
kind of switch.

Replacing it with an `isTerminal()` check would lose that
exhaustiveness guarantee and require a separate chain of
`instanceof` checks to figure out which terminal callback to
invoke. Strictly worse.

## Feeder cleanup — `DefaultAtom` and `DefaultJournal`

Both feeder implementations currently have the wrong
`tryAcquire`/`drainPermits` pattern. The code looks like:

```java
// WRONG — ignores tryAcquire's boolean return
semaphore.tryAcquire(1, TimeUnit.SECONDS);
semaphore.drainPermits();
```

Should be:

```java
// RIGHT — honors the return value, matches spec 017 pattern
if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
  semaphore.drainPermits();
}
```

The behavior difference: when `tryAcquire` returns false (the
1-second timeout elapsed without any permits arriving), there's
nothing stacked up to drain, so `drainPermits` should be skipped.
When it returns true (at least one `release()` happened during
the wait), there might be more permits stacked behind it, so
`drainPermits` clears them in a single call. This is the exact
pattern spec 017 established when it introduced the semaphore
nudge model.

**Locations to fix:**

- `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtom.java`
  — the Atom feeder loop (currently around line 199)
- `substrate-core/src/main/java/org/jwcarman/substrate/core/journal/DefaultJournal.java`
  — the Journal feeder loop (currently around line 254)

`DefaultMailbox.java` already has the correct pattern (carried
over from spec 017's original implementation), so it doesn't need
fixing. The Mailbox migration spec (spec 036) has the correct
pattern in its sketch; when Ralph implements it, the resulting
code will be correct on its first commit.

## `record` variable rename in `DefaultAtom`

`DefaultAtom.java` currently uses `record` as a local variable
name in two places:

- Inside `get()` (currently around line 81):
  ```java
  RawAtom record = atomSpi.read(key).orElseThrow(() -> new AtomExpiredException(key));
  ```
- Inside the feeder loop (currently around line 187):
  ```java
  Optional<RawAtom> record = atomSpi.read(key);
  ```

Java allows `record` as a local variable name because it's a
"restricted identifier" (reserved only in the context of type
declarations). But the IDE and compiler syntax highlighting often
treat it specially, readers expect it to be a keyword, and it
creates a cognitive stumble.

**Rename both occurrences to `raw`** to match the type name
(`RawAtom`) and avoid the keyword confusion:

```java
// get() body
RawAtom raw = atomSpi.read(key).orElseThrow(() -> new AtomExpiredException(key));
return new Snapshot<>(codec.decode(raw.value()), raw.token());

// feeder loop
Optional<RawAtom> raw = atomSpi.read(key);
if (raw.isEmpty()) {
  handoff.markExpired();
  return;
}
if (!raw.get().token().equals(lastToken.get())) {
  Snapshot<T> snap = new Snapshot<>(codec.decode(raw.get().value()), raw.get().token());
  handoff.push(snap);
  lastToken.set(snap.token());
}
```

`DefaultJournal` does not have this issue — check via
`grep '\brecord\s*=' DefaultJournal.java` to confirm before
declaring the fix complete.

## Scope boundary

Spec 037 does **not**:

- Touch primitive interfaces (`Atom`, `Journal`, `Mailbox`)
- Touch backend modules
- Change `NextResult` variant set — `Cancelled` is NOT added as a
  new variant. Interrupts are treated as "the caller's subscription
  is done," not as a new sealed case. The existing `Timeout` result
  is returned, and the caller's loop exits via `isActive()`
  returning false.
- Change the `NextHandoff` contract — handoff implementations
  continue to use the existing "catch + restore flag + return
  Timeout" pattern, which is correct at the handoff layer

Spec 037 ONLY touches:

- `substrate-api/.../NextResult.java` — add `isTerminal()` default
  method + two overrides on `Value` and `Timeout`
- `substrate-core/.../core/subscription/DefaultBlockingSubscription.java`
  — rewrite `next()` body with the cleanup and interrupt checks
- `substrate-core/.../core/subscription/DefaultCallbackSubscription.java`
  — add interrupt checks to the handler loop, switch to
  near-infinite pull duration, interrupt the handler thread from
  `cancel()`
- `substrate-core/.../core/atom/DefaultAtom.java` — wrap
  `tryAcquire` in `if`, rename `record` → `raw`
- `substrate-core/.../core/journal/DefaultJournal.java` — wrap
  `tryAcquire` in `if`
- New test file:
  `substrate-core/src/test/java/.../DefaultBlockingSubscriptionTest.java`
  — add tests for interrupt handling and `isTerminal` behavior
- Existing test file:
  `substrate-core/.../DefaultCallbackSubscriptionTest.java` — add
  interrupt-handling tests

## Acceptance criteria

### `NextResult<T>` changes

- [ ] `NextResult<T>` has a `default boolean isTerminal()` method
      returning `true`.
- [ ] `NextResult.Value<T>` overrides `isTerminal()` to return
      `false`.
- [ ] `NextResult.Timeout<T>` overrides `isTerminal()` to return
      `false`.
- [ ] `NextResult.Completed<T>`, `Expired<T>`, `Deleted<T>`,
      `Errored<T>` all use the default `true` (no override needed).
- [ ] Javadoc on `isTerminal` documents which variants are
      terminal and why.

### `DefaultBlockingSubscription.next` rewrite

- [ ] The `next(Duration)` method no longer contains the
      empty-cased switch from spec 033.
- [ ] `next` first checks `Thread.currentThread().isInterrupted()`
      before calling `pull`. If set, `done` is flipped and
      `NextResult.Timeout` is returned immediately.
- [ ] After `pull` returns, `next` checks `result.isTerminal()`
      and flips `done` if true.
- [ ] After handling the result, `next` checks the interrupt flag
      a second time. If set, `done` is flipped.
- [ ] The interrupt flag is NEVER cleared by `next` — when
      interrupts are detected, the flag is preserved for upstream
      handling (standard Java idiom).

### `DefaultCallbackSubscription` handler loop

- [ ] The handler loop first checks
      `Thread.currentThread().isInterrupted()` at the top of each
      iteration. If set, `done` is flipped and the loop exits.
- [ ] After the `pull` and switch dispatch, the loop checks the
      interrupt flag a second time. If set, `done` is flipped.
- [ ] The switch inside the handler loop is NOT refactored — each
      case does distinct callback work and benefits from the
      exhaustiveness check.
- [ ] The `HANDLER_POLL_INTERVAL` constant from spec 033 is
      **removed** and replaced with `MAX_POLL_DURATION =
      Duration.ofMillis(Long.MAX_VALUE)`. The handler loop no
      longer periodically wakes up to check `done` — it parks
      inside `handoff.pull` until a value, terminal, or interrupt
      arrives.
- [ ] `DefaultCallbackSubscription` tracks the handler thread in
      a `private final Thread handlerThread` field.
- [ ] `cancel()` calls `handlerThread.interrupt()` in addition to
      flipping `done` and running the canceller closure. The
      interrupt wakes the handler thread from its (effectively
      infinite) pull.

### Unit tests — `DefaultBlockingSubscriptionTest`

- [ ] A test verifies `NextResult.Value.isTerminal()` returns
      `false`.
- [ ] A test verifies `NextResult.Timeout.isTerminal()` returns
      `false`.
- [ ] A test verifies `NextResult.Completed.isTerminal()`,
      `Expired.isTerminal()`, `Deleted.isTerminal()`, and
      `Errored(cause).isTerminal()` all return `true`.

- [ ] A test verifies that calling `next(timeout)` on a subscription
      whose thread is pre-interrupted returns `NextResult.Timeout`
      immediately AND flips `isActive()` to false. The interrupt
      flag must still be set after the call — verified by asserting
      `Thread.currentThread().isInterrupted() == true`.

- [ ] A test verifies the "interrupt during pull" case: construct
      a subscription with a hand-made handoff that blocks on
      `pull`, spawn a thread that calls `next(timeout)`, interrupt
      that thread, assert that `next` returns within milliseconds,
      that the returned variant is `NextResult.Timeout`, and that
      `isActive()` returns false after the call.

- [ ] A test verifies the "loop exits cleanly under interrupt"
      case: start a consumer loop on a subscription, interrupt the
      loop thread, assert the loop exits within ~100 ms. This
      directly exercises the bug fix — without the fix, this test
      would hang or spin indefinitely.

- [ ] A test verifies that a Value result does NOT flip
      `isActive()` to false.

- [ ] A test verifies that a Timeout result (in the absence of an
      interrupt) does NOT flip `isActive()` to false.

- [ ] A test verifies that each terminal result
      (`Completed`/`Expired`/`Deleted`/`Errored`) flips
      `isActive()` to false.

### Unit tests — `DefaultCallbackSubscriptionTest`

- [ ] A test verifies that `cancel()` on an idle subscription
      causes the handler loop to exit within ~100 ms (not up to
      1 second). This directly exercises the "park forever, wake
      on interrupt" behavior — without it, the test would take
      up to 1 second.

- [ ] A test verifies that an idle subscription does zero
      periodic work: spawn a subscription, sleep for 3 seconds
      without pushing anything to the handoff, verify via thread
      instrumentation that the handler thread was not scheduled
      during that 3-second window (or at minimum that no
      `handoff.pull` call returned during that window).

- [ ] A test verifies that when the handler loop thread is
      interrupted externally (not via cancel), the loop exits
      within ~100 ms and does not continue invoking callbacks.

- [ ] A test verifies that interrupting the handler thread does
      NOT fire `onError` — interrupt is a cancel-like event, not
      an error.

- [ ] A test verifies that a value pushed into the handoff while
      the handler loop is parked inside pull wakes the handler
      thread immediately (within milliseconds). This exercises
      the "long pull, natural wake-up on queue put" path.

- [ ] The existing spec 033 callback tests (onNext dispatch,
      terminal callbacks firing, handler exception swallowing) all
      continue to pass unchanged.

### Feeder cleanup — `DefaultAtom` and `DefaultJournal`

- [ ] `DefaultAtom.java`'s feeder loop wraps `semaphore.tryAcquire`
      in an `if` that only calls `drainPermits` when `tryAcquire`
      returned `true`.
- [ ] `DefaultJournal.java`'s feeder loop wraps `semaphore.tryAcquire`
      in the same pattern.
- [ ] A grep for
      `semaphore\.tryAcquire\([^)]*\);\s*\n\s*semaphore\.drainPermits`
      in `substrate-core/src/main/java` returns zero matches —
      confirming no remaining unguarded `drainPermits` calls after
      an ignored `tryAcquire`.
- [ ] Existing Atom and Journal subscription tests (from specs 034
      and 035) continue to pass unchanged — this is a semantics-
      preserving refactor.

### `record` variable rename in `DefaultAtom`

- [ ] `DefaultAtom.java` no longer uses `record` as a local
      variable name. Verified by grep for `\brecord\s*=` in
      `substrate-core/src/main/java/.../atom/DefaultAtom.java`
      returning zero matches.
- [ ] Both occurrences (inside `get()` and inside the feeder
      loop) are renamed to `raw`.
- [ ] All existing `DefaultAtom` tests continue to pass unchanged.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] Apache 2.0 license headers on every modified file.
- [ ] The spec 033 tests that were written against the
      pre-cleanup `DefaultBlockingSubscription.next` implementation
      continue to pass. The rewrite should be semantically
      equivalent for every non-interrupt case — `isTerminal()` is
      just a cleaner way to express the same logic.
- [ ] Specs 034 and 035's subscription tests continue to pass
      unchanged after the feeder and variable-rename cleanups.

## Implementation notes

- The `isTerminal` default method is placed on the `NextResult`
  sealed interface itself rather than on a helper class, so
  callers never need to import a utility — the predicate lives
  where the type lives. Default methods on sealed interfaces are
  an established Java idiom (see `java.nio.file.Path`,
  `java.time.temporal.TemporalAccessor`).
- **Interrupt handling at the subscription layer is the right
  place for this fix**, not inside `DefaultNextQueue` or the
  individual `NextHandoff` implementations. The handoffs are
  strategy-specific, but the "consumer subscription is being torn
  down via thread interrupt" concern is subscription-layer. Every
  handoff implementation is free to continue using the standard
  Java idiom of "catch, restore flag, return Timeout."
- A `Cancelled` variant was considered and rejected. Treating
  interrupt as a new `NextResult` variant would force every caller
  to pattern-match on it, and the semantics overlap heavily with
  "the subscription was cancelled externally" which is already
  represented by `isActive() == false` after `cancel()`. Using
  `Timeout` + an `isActive()` flip keeps the sealed type small and
  the failure mode boring: "your next call got nothing, your
  subscription is no longer active."
- The interrupt flag preservation (`Thread.currentThread().interrupt()`
  via re-setting it, or just leaving it set when we detected it via
  `isInterrupted()`) is the standard Java idiom. Upstream code
  observing `Thread.currentThread().isInterrupted()` in an
  application-level shutdown handler will see the flag and behave
  correctly. Substrate doesn't clear the flag at any point.
- The double interrupt check in `next()` (once before `pull`, once
  after) is intentional. The first check handles "caller was
  already interrupted when they called next" and avoids a
  pointless `pull` call. The second check handles "caller was
  interrupted during the pull, handoff restored the flag before
  returning." Both cases need to flip `done`. Without both checks,
  one path leaks.
- If future work adds new handoff implementations (e.g., a
  hypothetical `DroppingHandoff`), they should follow the same
  interrupt-handling pattern as the three existing implementations:
  catch `InterruptedException`, call
  `Thread.currentThread().interrupt()` to restore the flag, return
  `NextResult.Timeout`. The subscription layer in spec 037's rewrite
  will handle the rest.
