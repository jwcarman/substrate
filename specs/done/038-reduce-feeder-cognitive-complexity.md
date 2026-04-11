# Reduce cognitive complexity of `startFeeder` methods

**Depends on: specs 033, 034, 035, 036, 037 must be completed.** This
is a post-implementation cleanup spec for the three `Default*`
primitives' feeder methods.

## What to build

SonarQube flags `DefaultAtom.startFeeder` at cognitive complexity 22
(limit 15). `DefaultJournal.startFeeder` and
`DefaultMailbox.startFeeder` share the same structural pattern and
are likely over the limit as well. All three have the same root
cause: a single method contains two nested lambdas, the inner
lambda contains a while loop with multiple nested conditionals and a
try/catch/catch/finally block, and the nesting levels compound
cognitive complexity at each layer.

Refactor all three feeder methods by extracting their component
parts into private helper methods. The refactor preserves behavior
exactly — every existing subscription test must pass without
modification. No new public API, no new behavior, no dependency
changes.

## The pattern — extract four helpers

Apply this same shape to each of the three `Default*.startFeeder`
methods:

### Helper 1: `handleNotification` — notifier-handler body

Extracts the lambda body passed to `notifier.subscribe`. Takes the
arguments the handler needs (key, payload, handoff, running flag,
semaphore), does the filter + mark-if-deleted + release pattern,
and returns void. Also **consolidate the `semaphore.release()` call**
that's currently duplicated in both branches of the existing
if/else — it should be pulled out of the branch and called
unconditionally at the end of the method.

### Helper 2: `runFeederLoop` — thread body

Extracts the top-level `try/catch/catch/finally` plus `while` loop
of the feeder thread. Takes the same captured state the lambda
currently uses. Calls into the next helper for each iteration's
work.

### Helper 3: `readAndPushIfChanged` (or primitive-specific equivalent) — one iteration's read + push

Extracts "do one read of the SPI, decide what to do, push or mark a
terminal state." Returns a `boolean` (or `void`, if the caller can
inspect state differently) indicating whether the loop should
continue.

For Journal, this helper is the one that advances the checkpoint
after a successful push. For Mailbox, this helper decides between
"push the delivered value and mark done" vs. "keep waiting." For
Atom, this helper checks the token and pushes only if it differs.

### Helper 4: `waitForNudge` — park on the semaphore

The existing `if (semaphore.tryAcquire(1, SECONDS)) { semaphore.drainPermits(); }`
pattern, extracted as a static method. Takes only the semaphore.
Throws `InterruptedException` — handled by the caller inside
`runFeederLoop`'s try block.

## Concrete refactor — `DefaultAtom`

Before (current code, lines 159-216):

```java
private Runnable startFeeder(CoalescingHandoff<Snapshot<T>> handoff, Snapshot<T> lastSeen) {
  AtomicBoolean running = new AtomicBoolean(true);
  Semaphore semaphore = new Semaphore(0);
  AtomicReference<String> lastToken =
      new AtomicReference<>(lastSeen != null ? lastSeen.token() : null);

  NotifierSubscription notifierSub =
      notifier.subscribe(
          (notifiedKey, payload) -> {
            if (!key.equals(notifiedKey)) {
              return;
            }
            if (DELETED_PAYLOAD.equals(payload)) {
              handoff.markDeleted();
              running.set(false);
              semaphore.release();
            } else {
              semaphore.release();
            }
          });

  Thread feederThread =
      Thread.ofVirtual()
          .name("substrate-atom-feeder", 0)
          .start(
              () -> {
                try {
                  while (running.get() && !Thread.currentThread().isInterrupted()) {
                    Optional<RawAtom> raw = atomSpi.read(key);
                    if (raw.isEmpty()) {
                      handoff.markExpired();
                      return;
                    }
                    if (!raw.get().token().equals(lastToken.get())) {
                      Snapshot<T> snap =
                          new Snapshot<>(codec.decode(raw.get().value()), raw.get().token());
                      handoff.push(snap);
                      lastToken.set(snap.token());
                    }
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
}
```

After:

```java
private Runnable startFeeder(CoalescingHandoff<Snapshot<T>> handoff, Snapshot<T> lastSeen) {
  AtomicBoolean running = new AtomicBoolean(true);
  Semaphore semaphore = new Semaphore(0);
  AtomicReference<String> lastToken =
      new AtomicReference<>(lastSeen != null ? lastSeen.token() : null);

  NotifierSubscription notifierSub = notifier.subscribe(
      (notifiedKey, payload) ->
          handleNotification(notifiedKey, payload, handoff, running, semaphore));

  Thread feederThread = Thread.ofVirtual()
      .name("substrate-atom-feeder", 0)
      .start(() -> runFeederLoop(handoff, running, semaphore, lastToken, notifierSub));

  return () -> {
    running.set(false);
    feederThread.interrupt();
    notifierSub.cancel();
  };
}

private void handleNotification(
    String notifiedKey,
    String payload,
    CoalescingHandoff<Snapshot<T>> handoff,
    AtomicBoolean running,
    Semaphore semaphore) {
  if (!key.equals(notifiedKey)) {
    return;
  }
  if (DELETED_PAYLOAD.equals(payload)) {
    handoff.markDeleted();
    running.set(false);
  }
  semaphore.release();
}

private void runFeederLoop(
    CoalescingHandoff<Snapshot<T>> handoff,
    AtomicBoolean running,
    Semaphore semaphore,
    AtomicReference<String> lastToken,
    NotifierSubscription notifierSub) {
  try {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      if (!readAndPushIfChanged(handoff, lastToken)) {
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

private boolean readAndPushIfChanged(
    CoalescingHandoff<Snapshot<T>> handoff,
    AtomicReference<String> lastToken) {
  Optional<RawAtom> raw = atomSpi.read(key);
  if (raw.isEmpty()) {
    handoff.markExpired();
    return false;
  }
  String currentToken = raw.get().token();
  if (!currentToken.equals(lastToken.get())) {
    Snapshot<T> snap = new Snapshot<>(codec.decode(raw.get().value()), currentToken);
    handoff.push(snap);
    lastToken.set(currentToken);
  }
  return true;
}

private static void waitForNudge(Semaphore semaphore) throws InterruptedException {
  if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
    semaphore.drainPermits();
  }
}
```

**Cognitive complexity breakdown after the refactor:**

| Method | Complexity |
|---|---|
| `startFeeder` | ~2 (pure wiring, lambdas delegate) |
| `handleNotification` | ~2 (two unnested ifs) |
| `runFeederLoop` | ~6 (while + inner if + try/catch/catch/finally) |
| `readAndPushIfChanged` | ~2 (two unnested ifs) |
| `waitForNudge` | 1 (single if) |

All well below 15.

## Refactor — `DefaultJournal`

`DefaultJournal.startFeeder` is 75 lines — bigger than Atom because
of the preload list handling and the completion-drain path. The
refactor structure is:

```java
private Runnable startFeeder(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    String startingCheckpoint,
    List<RawJournalEntry> preload) {
  // ... same setup (running, semaphore, checkpoint reference) ...
  // ... notifier subscription delegating to handleNotification ...
  // ... feeder thread delegating to runFeederLoop ...
  // ... canceller closure ...
}

private void handleNotification(
    String notifiedKey,
    String payload,
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicBoolean running,
    Semaphore semaphore) {
  // same as Atom's handleNotification — filter, check for deleted, release
}

private void runFeederLoop(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicBoolean running,
    Semaphore semaphore,
    AtomicReference<String> checkpoint,
    List<RawJournalEntry> preload,
    NotifierSubscription notifierSub) {
  try {
    pushPreload(handoff, checkpoint, preload);
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      readBatchAndPush(handoff, checkpoint);
      if (drainIfCompleted(handoff, checkpoint)) {
        return;
      }
      waitForNudge(semaphore);
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
}

private void pushPreload(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicReference<String> checkpoint,
    List<RawJournalEntry> preload) {
  for (RawJournalEntry raw : preload) {
    handoff.push(decode(raw));
    checkpoint.set(raw.id());
  }
}

private void readBatchAndPush(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicReference<String> checkpoint) {
  List<RawJournalEntry> batch = journalSpi.readAfter(key, checkpoint.get());
  for (RawJournalEntry raw : batch) {
    handoff.push(decode(raw));
    checkpoint.set(raw.id());
  }
}

/** Returns true if the journal is now completed and has been fully drained. */
private boolean drainIfCompleted(
    BlockingBoundedHandoff<JournalEntry<T>> handoff,
    AtomicReference<String> checkpoint) {
  if (!journalSpi.isComplete(key)) {
    return false;
  }
  // final drain: catch any entries appended concurrently with the
  // completion marker being set
  List<RawJournalEntry> finalBatch = journalSpi.readAfter(key, checkpoint.get());
  for (RawJournalEntry raw : finalBatch) {
    handoff.push(decode(raw));
    checkpoint.set(raw.id());
  }
  handoff.markCompleted();
  return true;
}

private static void waitForNudge(Semaphore semaphore) throws InterruptedException {
  if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
    semaphore.drainPermits();
  }
}
```

The Journal feeder loop splits into four focused helpers: push
preload, read-and-push each batch, check for completion (and drain
if so), wait for the next nudge. Each helper's complexity is low
(1-3) and `runFeederLoop` itself is just sequencing them.

## Refactor — `DefaultMailbox`

`DefaultMailbox.startFeeder` is 50 lines. The refactor pattern is
simpler than Journal because Mailbox has no checkpoint and no
completion drain:

```java
private Runnable startFeeder(SingleShotHandoff<T> handoff) {
  AtomicBoolean running = new AtomicBoolean(true);
  Semaphore semaphore = new Semaphore(0);

  NotifierSubscription notifierSub = notifier.subscribe(
      (notifiedKey, payload) ->
          handleNotification(notifiedKey, payload, handoff, running, semaphore));

  Thread feederThread = Thread.ofVirtual()
      .name("substrate-mailbox-feeder", 0)
      .start(() -> runFeederLoop(handoff, running, semaphore, notifierSub));

  return () -> {
    running.set(false);
    feederThread.interrupt();
    notifierSub.cancel();
  };
}

private void handleNotification(
    String notifiedKey,
    String payload,
    SingleShotHandoff<T> handoff,
    AtomicBoolean running,
    Semaphore semaphore) {
  if (!key.equals(notifiedKey)) {
    return;
  }
  if (DELETED_PAYLOAD.equals(payload)) {
    handoff.markDeleted();
    running.set(false);
  }
  semaphore.release();
}

private void runFeederLoop(
    SingleShotHandoff<T> handoff,
    AtomicBoolean running,
    Semaphore semaphore,
    NotifierSubscription notifierSub) {
  try {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      if (tryPushDelivery(handoff)) {
        return;   // single value delivered, feeder's job is done
      }
      waitForNudge(semaphore);
    }
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
  } catch (MailboxExpiredException e) {
    handoff.markExpired();
  } catch (RuntimeException e) {
    handoff.error(e);
  } finally {
    notifierSub.cancel();
  }
}

/** Returns true if a value was pushed (feeder should exit); false if still waiting. */
private boolean tryPushDelivery(SingleShotHandoff<T> handoff) {
  Optional<byte[]> value = mailboxSpi.get(key);
  if (value.isPresent()) {
    handoff.push(codec.decode(value.get()));
    return true;
  }
  return false;
}

private static void waitForNudge(Semaphore semaphore) throws InterruptedException {
  if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
    semaphore.drainPermits();
  }
}
```

Mailbox is the simplest of the three because its loop body is just
"try once; if delivered, done; else wait." One helper for the try,
one for the wait, one for the notification handler.

## Also: extract `DELETED_PAYLOAD` constant if not already

If any of the three primitives doesn't already have the
`DELETED_PAYLOAD = "__DELETED__"` constant as a static field,
extract it. At minimum, `DefaultAtom` already has it at line 44.
Check `DefaultJournal` and `DefaultMailbox` and add the same
constant if it's currently a string literal. This avoids scattered
magic strings.

## `waitForNudge` can be shared

The `waitForNudge` helper is identical across all three primitives.
It could live in a utility class (e.g., `FeederUtils` or
`SubscriptionSupport`) in `org.jwcarman.substrate.core.subscription`
and be called from each `Default*` implementation. This is optional
for this spec — if it adds more ceremony than it removes, just
keep a copy in each file. The copies are three lines each.

**Recommendation:** keep the copies for now. A utility class for
one three-line method is over-engineered. Revisit if another call
site for `waitForNudge` emerges.

## Acceptance criteria

### `DefaultAtom` refactor

- [ ] `DefaultAtom.startFeeder` cognitive complexity is ≤ 15 per
      SonarQube.
- [ ] The method is decomposed into `startFeeder`,
      `handleNotification`, `runFeederLoop`, `readAndPushIfChanged`,
      and `waitForNudge` helper methods.
- [ ] `handleNotification` consolidates the `semaphore.release()`
      call that was previously duplicated in both branches of the
      if/else.
- [ ] `waitForNudge` preserves the `if (tryAcquire) drainPermits`
      guard pattern from spec 037.
- [ ] Behavior is identical — all existing Atom subscription tests
      pass unchanged. No test file is modified.

### `DefaultJournal` refactor

- [ ] `DefaultJournal.startFeeder` cognitive complexity is ≤ 15.
- [ ] Extracted helpers: `handleNotification`, `runFeederLoop`,
      `pushPreload`, `readBatchAndPush`, `drainIfCompleted`,
      `waitForNudge`.
- [ ] The monotonic checkpoint advancement (checkpoint is updated
      only after successful push) is preserved in the extracted
      helpers.
- [ ] The final-drain behavior on completion detection is
      preserved.
- [ ] Behavior is identical — all existing Journal subscription
      tests pass unchanged, including
      `JournalSubscriptionTest`, `ConsumerEscapeHatchTest`, and
      the backpressure tests.

### `DefaultMailbox` refactor

- [ ] `DefaultMailbox.startFeeder` cognitive complexity is ≤ 15.
- [ ] Extracted helpers: `handleNotification`, `runFeederLoop`,
      `tryPushDelivery`, `waitForNudge`.
- [ ] The "exit after single successful push" behavior is
      preserved.
- [ ] The `MailboxExpiredException` catch is preserved.
- [ ] Behavior is identical — all existing Mailbox subscription
      tests pass unchanged.

### Constant hygiene

- [ ] `DefaultAtom`, `DefaultJournal`, and `DefaultMailbox` each
      have a `private static final String DELETED_PAYLOAD =
      "__DELETED__";` constant, declared at the top of the class.
      No string literal `"__DELETED__"` appears anywhere in the
      three files (except inside the constant declaration
      itself).

### Build

- [ ] `./mvnw spotless:check` passes.
- [ ] `./mvnw verify` passes with zero test failures and zero
      regressions.
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] SonarQube analysis (if available locally or in CI) reports
      zero cognitive complexity violations for any of the three
      `startFeeder` methods.

## Implementation notes

- This is a pure refactor. Zero behavior changes, zero public API
  changes, zero test changes. If a test needs modification, the
  refactor has changed behavior and something is wrong — investigate
  before "fixing" the test.
- The helper method signatures take the captured state as explicit
  parameters (handoff, running, semaphore, etc.). They could
  alternatively take the captured state as instance fields on a
  private static inner class — this is a style choice, not a
  correctness concern. The explicit-parameter approach is used
  above because it keeps the helpers callable as plain instance
  methods without a wrapper class, which matches Java's default
  style and minimizes noise.
- The `handleNotification` helper's semaphore.release() consolidation
  is a small behavior-equivalent simplification — previously the
  code called release in both the if and else branches (which was
  correct, but duplicated). Pulling it out of the branches reduces
  the if/else to a simple if and removes the duplication.
- The `waitForNudge` helper is declared `static` because it uses no
  instance state. Keep it `static` across all three primitives
  where applicable.
- The Journal feeder's `drainIfCompleted` is load-bearing for
  correctness: the `isComplete` check and the final drain must
  happen together inside this helper so that entries appended
  between the previous batch read and the completion marker are
  still delivered. The existing code does this correctly in the
  monolithic version; the refactor preserves it.
- SonarQube's cognitive complexity metric charges nesting heavily.
  The biggest wins come from **flattening nesting**, not just
  breaking up lines. Each extracted helper reduces its own
  complexity because it's at zero nesting at the start of its
  method body, even if it has the same number of branches as
  before.
- If you're tempted to go further — e.g., extracting a common
  abstract feeder base class for all three primitives — resist.
  The three feeders have enough differences (Atom's token comparison,
  Journal's checkpoint and drain, Mailbox's one-shot exit) that a
  common base class would need generics, template methods, and
  hook methods, which adds more cognitive complexity than it
  removes. Three parallel classes with similar structure is the
  right level of abstraction.
