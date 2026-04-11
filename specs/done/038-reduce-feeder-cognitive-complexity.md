# Consolidate feeder scaffolding into `FeederSupport`

**Depends on: specs 033, 034, 035, 036, 037 must be completed.** This
is a post-implementation refactor for the three `Default*` primitives'
feeder methods.

## What to build

Two related problems are addressed by a single refactor:

1. **SonarQube flags `DefaultAtom.startFeeder` at cognitive complexity
   22** (limit 15). `DefaultJournal.startFeeder` (~75 lines) and
   `DefaultMailbox.startFeeder` (~50 lines) share the same structural
   pattern and are likely over the limit as well.

2. **The three feeder methods are largely duplicated code.** The
   notifier subscription handler, semaphore wait pattern, thread
   lifecycle setup, exception handling, and canceller closure are all
   verbatim-identical across the three implementations. Only the
   primitive-specific "do one iteration" logic varies.

Rather than fix these separately, we kill two birds with one stone:
**extract a shared `FeederSupport` class** that encapsulates the
common scaffolding, and reduce each primitive's `startFeeder` to a
thin wrapper that supplies its primitive-specific iteration step via
a lambda. The shared class solves the duplication problem
directly, and the dramatic shrinkage of each `startFeeder` method
solves the cognitive complexity problem as a side effect.

## The identical patterns across the three feeders

Before the refactor, all three `Default*.startFeeder` methods
contain these verbatim-identical blocks:

**Notifier handler** (identical in Atom, Journal, Mailbox):

```java
notifier.subscribe(
    (notifiedKey, payload) -> {
      if (!key.equals(notifiedKey)) {
        return;
      }
      if ("__DELETED__".equals(payload)) {
        handoff.markDeleted();
        running.set(false);
      }
      semaphore.release();
    });
```

**Semaphore wait** (identical):

```java
if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
  semaphore.drainPermits();
}
```

**Thread lifecycle setup and exception handling** (identical
structure, differing only in primitive-specific catches for
`JournalExpiredException` / `MailboxExpiredException`):

```java
AtomicBoolean running = new AtomicBoolean(true);
Semaphore semaphore = new Semaphore(0);
NotifierSubscription notifierSub = /* ... */;

Thread feederThread = Thread.ofVirtual()
    .name("substrate-X-feeder", 0)
    .start(() -> {
      try {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
          // primitive-specific work here
          if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
            semaphore.drainPermits();
          }
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
```

The only varying parts:

- **Thread name** (`substrate-atom-feeder`, `substrate-journal-feeder`,
  `substrate-mailbox-feeder`)
- **Handoff type** (`CoalescingHandoff`, `BlockingBoundedHandoff`,
  `SingleShotHandoff`)
- **Primitive-specific state** captured in the feeder closure (Atom:
  `lastToken`; Journal: `checkpoint` + `preload`; Mailbox: nothing
  extra)
- **Iteration work** — Atom compares token and pushes if changed,
  Journal reads after checkpoint and checks completion, Mailbox
  tries to read the delivered value and exits if found

## The extraction

### New file: `FeederSupport.java`

```java
package org.jwcarman.substrate.core.subscription;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;

/**
 * Shared scaffolding for feeder threads across the three primitives
 * ({@code Atom}, {@code Journal}, {@code Mailbox}). Encapsulates the
 * notifier subscription, semaphore-based wake-up mechanism, thread
 * lifecycle, and exception handling that are identical across all
 * three feeders. Primitive-specific work is supplied via a
 * {@link FeederStep} lambda.
 *
 * <p>Each primitive's {@code Default*.startFeeder} method calls
 * {@link #start} to spawn a feeder virtual thread and returns the
 * canceller closure as the subscription's cleanup hook.
 */
public final class FeederSupport {

  private static final String DELETED_PAYLOAD = "__DELETED__";

  private FeederSupport() {}

  /**
   * Start a feeder virtual thread that drives {@code step} in a loop
   * until the step returns {@code false}, the thread is interrupted,
   * or a terminal condition fires.
   *
   * <p>The feeder thread subscribes to the {@code notifier} for the
   * given {@code key} and uses a semaphore to wake up on
   * notifications. On a {@code "__DELETED__"} payload, the feeder
   * calls {@code handoff.markDeleted()} and exits its loop. On any
   * uncaught {@link RuntimeException} from the step, the feeder
   * calls {@code handoff.error(cause)} and exits. The notifier
   * subscription is always cancelled in a {@code finally} block.
   *
   * @param key the backend-qualified key this feeder is associated
   *            with — used to filter notifications
   * @param notifier the notifier SPI to subscribe to
   * @param handoff the handoff that the step will push values into;
   *            also the target of {@code markDeleted} and
   *            {@code error} when those events fire
   * @param threadName the virtual thread name prefix (e.g.,
   *            {@code "substrate-atom-feeder"})
   * @param step the primitive-specific work to run on each iteration
   * @return a canceller closure that, when run, stops the feeder
   *            thread (by interrupting it) and cancels the notifier
   *            subscription
   */
  public static Runnable start(
      String key,
      NotifierSpi notifier,
      NextHandoff<?> handoff,
      String threadName,
      FeederStep step) {

    AtomicBoolean running = new AtomicBoolean(true);
    Semaphore semaphore = new Semaphore(0);

    NotifierSubscription notifierSub = notifier.subscribe(
        (notifiedKey, payload) ->
            handleNotification(key, notifiedKey, payload, handoff, running, semaphore));

    Thread feederThread = Thread.ofVirtual()
        .name(threadName, 0)
        .start(() -> runLoop(running, semaphore, handoff, step, notifierSub));

    return () -> {
      running.set(false);
      feederThread.interrupt();
      notifierSub.cancel();
    };
  }

  private static void handleNotification(
      String expectedKey,
      String notifiedKey,
      String payload,
      NextHandoff<?> handoff,
      AtomicBoolean running,
      Semaphore semaphore) {
    if (!expectedKey.equals(notifiedKey)) {
      return;
    }
    if (DELETED_PAYLOAD.equals(payload)) {
      handoff.markDeleted();
      running.set(false);
    }
    semaphore.release();
  }

  private static void runLoop(
      AtomicBoolean running,
      Semaphore semaphore,
      NextHandoff<?> handoff,
      FeederStep step,
      NotifierSubscription notifierSub) {
    try {
      while (running.get() && !Thread.currentThread().isInterrupted()) {
        if (!step.runOnce()) {
          return;
        }
        waitForNudge(semaphore);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      handoff.error(e);
    } finally {
      notifierSub.cancel();
    }
  }

  private static void waitForNudge(Semaphore semaphore) throws InterruptedException {
    if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
      semaphore.drainPermits();
    }
  }

  /**
   * One iteration of a primitive's feeder work. Invoked repeatedly by
   * {@link FeederSupport#start}'s internal loop.
   *
   * <p>Implementations capture primitive-specific state via closure
   * from the enclosing {@code startFeeder} method. Typical state
   * includes an atomic reference to the last-known state (token or
   * checkpoint), the handoff reference, and the codec.
   *
   * <p>Implementations may catch primitive-specific exceptions (e.g.,
   * {@code JournalExpiredException}, {@code MailboxExpiredException})
   * inside the step body to map them to the appropriate handoff
   * terminal state. Any other uncaught {@code RuntimeException} will
   * be caught by {@link FeederSupport}'s outer catch and delivered
   * as {@code NextResult.Errored} via {@code handoff.error}.
   */
  @FunctionalInterface
  public interface FeederStep {

    /**
     * Runs one iteration of the feeder's work — typically a single
     * SPI read and zero-or-more pushes into the handoff.
     *
     * @return {@code true} to continue the feeder loop; {@code false}
     *         to exit the feeder thread cleanly. Returning false is
     *         appropriate when the primitive has reached a terminal
     *         state (expired, deleted, single-delivery complete) and
     *         the step has already called the relevant
     *         {@code mark*} method on the handoff.
     */
    boolean runOnce() throws InterruptedException;
  }
}
```

### `DefaultAtom.startFeeder` after the refactor

```java
private Runnable startFeeder(
    CoalescingHandoff<Snapshot<T>> handoff, Snapshot<T> lastSeen) {
  AtomicReference<String> lastToken = new AtomicReference<>(
      lastSeen != null ? lastSeen.token() : null);

  return FeederSupport.start(
      key,
      notifier,
      handoff,
      "substrate-atom-feeder",
      () -> {
        Optional<RawAtom> raw = atomSpi.read(key);
        if (raw.isEmpty()) {
          handoff.markExpired();
          return false;
        }
        String currentToken = raw.get().token();
        if (!currentToken.equals(lastToken.get())) {
          Snapshot<T> snap =
              new Snapshot<>(codec.decode(raw.get().value()), currentToken);
          handoff.push(snap);
          lastToken.set(currentToken);
        }
        return true;
      });
}
```

**Lines of code:** ~18 (down from ~58).
**Cognitive complexity:** ~5 (well under 15).

### `DefaultJournal.startFeeder` after the refactor

The Journal step is bigger because there's more work per iteration
(preload handling, checkpoint advancement, completion detection,
final drain). The step can further delegate to private methods if
it's still over the complexity limit:

```java
private Runnable startFeeder(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    String startingCheckpoint,
    List<RawJournalEntry> preload) {
  AtomicReference<String> checkpoint = new AtomicReference<>(startingCheckpoint);
  AtomicBoolean preloaded = new AtomicBoolean(false);

  return FeederSupport.start(
      key,
      notifier,
      handoff,
      "substrate-journal-feeder",
      () -> runOneIteration(handoff, checkpoint, preloaded, preload));
}

private boolean runOneIteration(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicReference<String> checkpoint,
    AtomicBoolean preloaded,
    List<RawJournalEntry> preload) {
  pushPreloadIfNeeded(handoff, checkpoint, preloaded, preload);
  if (!readBatchAndPush(handoff, checkpoint)) {
    return false;
  }
  return !drainIfCompleted(handoff, checkpoint);
}

private void pushPreloadIfNeeded(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicReference<String> checkpoint,
    AtomicBoolean preloaded,
    List<RawJournalEntry> preload) {
  if (!preloaded.compareAndSet(false, true)) {
    return;
  }
  for (RawJournalEntry raw : preload) {
    handoff.push(decode(raw));
    checkpoint.set(raw.id());
  }
}

private boolean readBatchAndPush(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicReference<String> checkpoint) {
  try {
    List<RawJournalEntry> batch = journalSpi.readAfter(key, checkpoint.get());
    for (RawJournalEntry raw : batch) {
      handoff.push(decode(raw));
      checkpoint.set(raw.id());
    }
    return true;
  } catch (JournalExpiredException e) {
    handoff.markExpired();
    return false;
  }
}

/** Returns true if the journal has been completed and fully drained. */
private boolean drainIfCompleted(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicReference<String> checkpoint) {
  if (!journalSpi.isComplete(key)) {
    return false;
  }
  try {
    List<RawJournalEntry> finalBatch = journalSpi.readAfter(key, checkpoint.get());
    for (RawJournalEntry raw : finalBatch) {
      handoff.push(decode(raw));
      checkpoint.set(raw.id());
    }
  } catch (JournalExpiredException e) {
    handoff.markExpired();
    return true;   // terminate the feeder; markExpired wins over markCompleted
  }
  handoff.markCompleted();
  return true;
}
```

**Lines of code:** ~55 total (down from ~75), split into four
smaller methods each with complexity well under 15.

### `DefaultMailbox.startFeeder` after the refactor

```java
private Runnable startFeeder(SingleShotHandoff<T> handoff) {
  return FeederSupport.start(
      key,
      notifier,
      handoff,
      "substrate-mailbox-feeder",
      () -> {
        try {
          Optional<byte[]> value = mailboxSpi.get(key);
          if (value.isPresent()) {
            handoff.push(codec.decode(value.get()));
            return false;   // single delivery done, feeder exits
          }
          return true;
        } catch (MailboxExpiredException e) {
          handoff.markExpired();
          return false;
        }
      });
}
```

**Lines of code:** ~17 (down from ~50). The simplest of the three —
Mailbox's iteration is just "try once, exit on success or expiry."

## Net impact

| File | Before | After |
|---|---|---|
| `DefaultAtom.startFeeder` | ~58 lines | ~18 lines |
| `DefaultJournal.startFeeder` + helpers | ~75 lines | ~55 lines (across 4 methods) |
| `DefaultMailbox.startFeeder` | ~50 lines | ~17 lines |
| **Primitive total** | **~183** | **~90** |
| `FeederSupport` (new) | 0 | ~90 lines (including Javadoc) |
| **Grand total** | **~183** | **~180** |

Similar total line count but drastically different distribution:

- ~140 lines of duplicated scaffolding collapse into ~90 lines of
  well-documented shared helper
- Each primitive's `startFeeder` becomes small and focused on its
  unique iteration logic
- All three feeders pass the SonarQube cognitive complexity check
- Changes to the feeder pattern (e.g., adding metrics, changing the
  poll interval, handling a new notification payload) only need to
  be made in one place

## Scope boundary

Spec 038 does **not**:

- Touch any primitive interface (`Atom`, `Journal`, `Mailbox`)
- Touch any existing test — behavior is preserved exactly
- Touch any backend module
- Change the public API surface in any way
- Change the `NextHandoff` contract or its three implementations
- Change the `Default*Subscription` types from spec 033

Spec 038 ONLY touches:

- **New file** —
  `substrate-core/.../core/subscription/FeederSupport.java`
- **Modified** —
  `substrate-core/.../core/atom/DefaultAtom.java` (rewrite
  `startFeeder` to delegate to `FeederSupport`)
- **Modified** —
  `substrate-core/.../core/journal/DefaultJournal.java` (rewrite
  `startFeeder`, extract private helpers for the iteration work)
- **Modified** —
  `substrate-core/.../core/mailbox/DefaultMailbox.java` (rewrite
  `startFeeder` to delegate to `FeederSupport`)
- **New test file** —
  `substrate-core/src/test/java/.../core/subscription/FeederSupportTest.java`
  — unit tests for `FeederSupport` itself (notifier handler,
  loop exit on step returning false, interrupt handling, exception
  propagation)

## Acceptance criteria

### `FeederSupport` class

- [ ] `org.jwcarman.substrate.core.subscription.FeederSupport` exists,
      is `public final`, has a private constructor.
- [ ] `FeederSupport.start(key, notifier, handoff, threadName, step)`
      is a public static method with the signature shown above.
- [ ] `FeederSupport.FeederStep` is a public nested
      `@FunctionalInterface` with one method
      `boolean runOnce() throws InterruptedException`.
- [ ] `FeederSupport` has class-level Javadoc explaining its purpose
      and the role of the `FeederStep` lambda.
- [ ] `FeederSupport.DELETED_PAYLOAD` is a private static final
      String constant equal to `"__DELETED__"`.
- [ ] The `handleNotification` helper is private static and
      consolidates the notifier filter + mark-if-deleted +
      semaphore.release pattern.
- [ ] The `runLoop` helper is private static and contains the
      while loop + try/catch/catch/finally.
- [ ] The `waitForNudge` helper is private static and contains the
      `if (semaphore.tryAcquire(...)) semaphore.drainPermits();`
      pattern.

### `DefaultAtom` refactor

- [ ] `DefaultAtom.startFeeder` is rewritten to delegate to
      `FeederSupport.start(...)` with a lambda step that performs
      the Atom-specific token comparison and push.
- [ ] `DefaultAtom` no longer imports `Semaphore`,
      `NotifierSubscription`, or `AtomicBoolean` — these are now
      encapsulated in `FeederSupport`. (It still imports
      `AtomicReference` because `lastToken` lives in the
      closure.)
- [ ] `DefaultAtom.startFeeder`'s cognitive complexity is ≤ 15.
- [ ] All existing Atom subscription tests pass unchanged.

### `DefaultJournal` refactor

- [ ] `DefaultJournal.startFeeder` is rewritten to delegate to
      `FeederSupport.start(...)` with a lambda step that delegates
      to private helper methods (`pushPreloadIfNeeded`,
      `readBatchAndPush`, `drainIfCompleted`).
- [ ] Each helper method's cognitive complexity is ≤ 15.
- [ ] The monotonic checkpoint advancement is preserved
      (checkpoint is updated only after successful push).
- [ ] The final-drain behavior on completion detection is
      preserved.
- [ ] All existing Journal subscription tests pass unchanged,
      including `JournalSubscriptionTest`, `ConsumerEscapeHatchTest`,
      and backpressure tests.

### `DefaultMailbox` refactor

- [ ] `DefaultMailbox.startFeeder` is rewritten to delegate to
      `FeederSupport.start(...)` with a lambda step that tries to
      read the delivered value and returns false after a
      successful push.
- [ ] `DefaultMailbox.startFeeder`'s cognitive complexity is ≤ 15.
- [ ] The "exit after single successful push" behavior is
      preserved.
- [ ] `MailboxExpiredException` is caught inside the step and
      mapped to `handoff.markExpired(); return false;`.
- [ ] All existing Mailbox subscription tests pass unchanged.

### `FeederSupport` unit tests

- [ ] `FeederSupportTest` exists and exercises the class in
      isolation (without any primitive).
- [ ] A test verifies that a step returning `true` causes the loop
      to continue and the step to be invoked again after a
      notification or timeout.
- [ ] A test verifies that a step returning `false` causes the
      feeder to exit cleanly.
- [ ] A test verifies that a step throwing an uncaught
      `RuntimeException` causes the feeder to call
      `handoff.error(cause)` and exit.
- [ ] A test verifies that a `"__DELETED__"` notification payload
      causes the feeder to call `handoff.markDeleted()` and exit.
- [ ] A test verifies that the canceller closure stops the feeder
      thread within a short time (Awaitility, ~100 ms).
- [ ] A test verifies that interrupting the feeder thread (via
      the canceller) exits the loop cleanly without firing
      `error`.

### Cognitive complexity

- [ ] SonarQube analysis reports zero cognitive complexity
      violations for any method in `DefaultAtom`, `DefaultJournal`,
      `DefaultMailbox`, or `FeederSupport`.
- [ ] The SonarQube warning the user screenshot'd on
      `DefaultAtom.startFeeder` is resolved.

### Build

- [ ] `./mvnw spotless:check` passes.
- [ ] `./mvnw verify` passes with zero test failures and zero
      regressions.
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] Apache 2.0 license headers on every new file.

## Implementation notes

- `FeederSupport.start` takes `NextHandoff<?>` as a wildcard type
  because the helper doesn't care about the value type — only the
  caller's step lambda does. This lets all three primitives use the
  same method without generic parameter explosion in the helper's
  signature.
- `FeederStep.runOnce` throws `InterruptedException` because the
  helper's loop needs to catch interrupts uniformly. Steps that
  don't do anything interruptible can still declare the throws
  (Java doesn't require catching it if the method signature
  permits propagation) — Atom's step doesn't catch anything, for
  example, because its `atomSpi.read` is synchronous and doesn't
  throw InterruptedException.
- **The `runLoop` catches `RuntimeException` generically** and
  delivers it via `handoff.error(cause)`. Primitive-specific
  exception types like `JournalExpiredException` and
  `MailboxExpiredException` are `RuntimeException` subclasses, so
  if the step didn't catch them itself, the `runLoop` would catch
  them and turn them into `Errored` (wrong semantic). That's why
  each primitive's step must catch its own expected exceptions
  (`JournalExpiredException` in Journal's step,
  `MailboxExpiredException` in Mailbox's step) and map them to
  the correct handoff terminal state.
- **Atom doesn't need to catch anything** because Atom's expiry
  is signaled by `atomSpi.read(key)` returning `Optional.empty()`,
  not by throwing. The step's `if (raw.isEmpty())` branch handles
  this case.
- The `AtomicBoolean preloaded` pattern in Journal's step is the
  cleanest way to run the preload exactly once across feeder
  iterations. The flag is captured in the closure and flipped on
  the first iteration. Don't extract the preload into a separate
  helper that runs outside the loop — that would break the
  "everything inside FeederSupport.start" encapsulation and
  require a different entry point.
- If the Journal step's `runOneIteration` is still over the
  cognitive complexity limit after extraction, further decompose
  it into smaller helpers. The current sketch decomposes into
  three helpers (`pushPreloadIfNeeded`, `readBatchAndPush`,
  `drainIfCompleted`), each with ≤5 complexity.
- **The primitive-specific exception catches live in the step
  lambda, not in FeederSupport**, because only the step knows
  which exceptions are "expected terminal states" vs "unexpected
  errors." A refactor that tries to push exception handling into
  FeederSupport via some kind of exception-to-action map would
  add complexity without removing duplication.
- The `FeederSupport` class is `final` and has a private
  constructor to signal that it's a pure utility — not meant to
  be extended or instantiated. The helpers inside (`handleNotification`,
  `runLoop`, `waitForNudge`) are all private static to keep the
  public surface minimal.
- **Don't create a `FeederSupport` for a single primitive.** If
  someday a new primitive is added that doesn't fit this pattern,
  that's fine — it doesn't have to use `FeederSupport`. The helper
  is for the three primitives whose feeder code is currently
  duplicated. If it only fits two of the three, that's OK too —
  the third can inline its own version. Don't force-fit.
