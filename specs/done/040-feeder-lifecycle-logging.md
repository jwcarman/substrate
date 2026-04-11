# Feeder thread lifecycle logging in `FeederSupport`

**Depends on: spec 038 must be completed.** This spec modifies
`FeederSupport`, which was introduced in spec 038 to consolidate
feeder scaffolding across `DefaultAtom`, `DefaultJournal`, and
`DefaultMailbox`.

## What to build

Today the `substrate-core` module is largely silent about feeder
thread lifecycle. If a feeder starts, exits, or catches an unexpected
error, nothing is logged. This makes it hard to diagnose issues in
production — an operator cannot tell from logs whether a
subscription's feeder is alive, has exited cleanly, or died from an
unexpected exception.

Because spec 038 consolidated all three primitives' feeder loops into
`FeederSupport.runLoop`, we can add lifecycle logging in exactly one
place and cover all three primitives at once. No changes to
`DefaultAtom`, `DefaultJournal`, or `DefaultMailbox` are needed.

Add three log statements to `FeederSupport`:

1. **`DEBUG` on feeder start** — logged from inside `runLoop` before
   entering the while loop.
2. **`DEBUG` on clean feeder exit** — logged from inside the
   `finally` block (after the `notifierSub.cancel()`), so it fires
   whether the loop exited because the step returned false, because
   `running` flipped to false, because of an interrupt, or because
   of a caught `RuntimeException`.
3. **`WARN` on unexpected `RuntimeException`** — logged inside the
   existing `catch (RuntimeException e)` block, immediately before
   `handoff.error(e)`. This is the one that matters most for
   production diagnosis: a silently-swallowed `RuntimeException`
   delivered only as `NextResult.Errored` to the subscriber is easy
   to miss.

The logger uses Apache commons-logging, matching the existing
convention in `DefaultCallbackSubscription` and `Sweeper`. Messages
use string concatenation (commons-logging does not support `{}`
placeholders).

## The three log statements

### Logger declaration

Added near the top of `FeederSupport`:

```java
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public final class FeederSupport {

  private static final Log log = LogFactory.getLog(FeederSupport.class);

  // ... rest of class
}
```

### `runLoop` with logging added

```java
private static void runLoop(
    AtomicBoolean running,
    Semaphore semaphore,
    NextHandoff<?> handoff,
    FeederStep step,
    NotifierSubscription notifierSub,
    String threadName,
    String key) {
  if (log.isDebugEnabled()) {
    log.debug("Feeder '" + threadName + "' started for key '" + key + "'");
  }
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
    log.warn(
        "Feeder '" + threadName + "' for key '" + key + "' caught unexpected error", e);
    handoff.error(e);
  } finally {
    notifierSub.cancel();
    if (log.isDebugEnabled()) {
      log.debug("Feeder '" + threadName + "' exited for key '" + key + "'");
    }
  }
}
```

Note that `runLoop` now takes two extra parameters — `threadName` and
`key` — so that the log messages can identify which feeder is
starting, stopping, or failing. Both values are already available in
`start`'s scope; this just threads them through.

### `start` call updated to pass the new parameters

```java
Thread feederThread = Thread.ofVirtual()
    .name(threadName, 0)
    .start(() -> runLoop(running, semaphore, handoff, step, notifierSub, threadName, key));
```

The rest of `FeederSupport.start` is unchanged.

## Why DEBUG for start/exit and WARN for errors

- **Start/exit** are high-frequency events in a healthy system —
  every subscription creates and eventually tears down a feeder. At
  `INFO` these would spam logs. `DEBUG` is the right level: invisible
  by default, but available when an operator turns it on to
  investigate a specific subscription.
- **Unexpected `RuntimeException`** is rare in a healthy system and
  indicates a real problem that an operator should see. `WARN` (not
  `ERROR`) is appropriate because the error *is* delivered to the
  subscriber via `handoff.error(cause)` — the subscription's
  consumer still gets to handle it — but the operator should know
  it happened because it likely indicates a backend SPI bug or a
  serialization failure.

Primitive-specific terminal states like `JournalExpiredException`,
`MailboxExpiredException`, or an atom returning `Optional.empty()`
are **not** logged by `FeederSupport` because they are caught
inside the step lambda, not by `runLoop`'s catch. These are normal
terminal events, not errors, and the step owns the mapping to
`markExpired()`.

## Scope boundary

Spec 040 does **not**:

- Touch `DefaultAtom`, `DefaultJournal`, or `DefaultMailbox`
- Add logging anywhere else in `substrate-core`
- Add per-push or per-notification logging (would be too chatty)
- Introduce a new logging dependency — uses the existing
  Apache commons-logging that the module already depends on
- Change the `FeederSupport.start` public signature
- Change the `FeederStep` interface
- Change any existing test behavior

Spec 040 ONLY touches:

- **Modified** —
  `substrate-core/.../core/subscription/FeederSupport.java`
  (add logger, add three log statements, thread `threadName` and
  `key` through to `runLoop`)
- **Modified (possibly)** —
  `substrate-core/src/test/java/.../core/subscription/FeederSupportTest.java`
  — only if existing tests need signature updates. No new tests
  required; the logging is observable behavior that would be
  tedious to assert on and adds little value beyond the manual
  log-output sanity check during the verify run.

## Acceptance criteria

### Logger

- [ ] `FeederSupport` declares a `private static final Log log`
      field obtained from `LogFactory.getLog(FeederSupport.class)`.
- [ ] The logger imports are `org.apache.commons.logging.Log` and
      `org.apache.commons.logging.LogFactory` (matching
      `DefaultCallbackSubscription` and `Sweeper`).

### Start log

- [ ] A `log.debug(...)` call fires at the top of `runLoop`, before
      entering the while loop.
- [ ] The message includes both the `threadName` and the `key`.
- [ ] The debug call is guarded by `if (log.isDebugEnabled())` to
      avoid unnecessary string concatenation when debug logging is
      off.

### Exit log

- [ ] A `log.debug(...)` call fires inside the `finally` block,
      after `notifierSub.cancel()`.
- [ ] The message includes both the `threadName` and the `key`.
- [ ] The debug call is guarded by `if (log.isDebugEnabled())`.
- [ ] The exit log fires for all exit paths: normal loop
      termination (running=false), step returning false, interrupt,
      and caught `RuntimeException`.

### Error log

- [ ] A `log.warn(...)` call fires inside the existing
      `catch (RuntimeException e)` block, immediately before
      `handoff.error(e)`.
- [ ] The message includes both the `threadName` and the `key`.
- [ ] The throwable is passed as the second argument to `log.warn`
      so the stack trace is captured.
- [ ] `handoff.error(e)` is still called after the log — the log
      must not replace error delivery, only supplement it.

### Signature plumbing

- [ ] `runLoop` takes `String threadName` and `String key` as
      additional parameters.
- [ ] The `start` method's call site passes both values through
      from its own scope.
- [ ] The public signature of `FeederSupport.start` is unchanged.

### Build

- [ ] `./mvnw spotless:check` passes.
- [ ] `./mvnw verify -DskipITs` passes with zero test failures and
      zero regressions.
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] No new dependency added to `substrate-core/pom.xml`.

## Implementation notes

- The `isDebugEnabled()` guard is worth keeping around the DEBUG
  calls because each call builds a concatenated string. At WARN the
  guard is unnecessary — warnings are rare enough that the
  concatenation cost is irrelevant.
- If `FeederSupportTest` has tests that construct a `runLoop` call
  directly (unlikely — the tests go through the public `start`
  method), they will need their call sites updated to pass the two
  new parameters. Most likely no test changes are needed because
  `runLoop` is private and tests only touch `start`.
- Do not add a log statement inside `handleNotification` for the
  `__DELETED__` case — that event is already observable via the
  exit debug log when the feeder's loop terminates.
- Do not add per-iteration logging (e.g., "step ran, returned
  true"). That would be far too chatty — a healthy feeder runs its
  step on every notification, which for an active journal could be
  thousands of times per second.
- The existing `catch (InterruptedException e)` block does **not**
  get a warn log — interrupts are the normal cancellation path and
  shouldn't look like an error. The exit debug log in `finally`
  covers the interrupt case already.
