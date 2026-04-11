# Replace pollDelay sleeps with latches in substrate-core subscription tests

## What to build

Several tests in `substrate-core` wait for a callback to fire by
doing `await().pollDelay(N).atMost(...).until(() -> true)` — which is
functionally equivalent to `Thread.sleep(N)` dressed up in Awaitility
clothing. These sites are vestigial hold-overs from the Thread.sleep
triage in spec 046, where a blanket pattern was applied to avoid the
S2925 rule. They work, but they're brittle in two ways:

1. **Time-based assertion that an event happened.** "After sleeping
   50ms, assert the callback fired" assumes 50ms is enough. On a
   loaded CI runner, it might not be. If the delay is generous
   enough to never fail, it's also longer than necessary on every
   clean run.
2. **Non-deterministic ordering.** If the callback fires in 2ms,
   the test still waits the full 50ms. Over ~10 such sites that
   adds up to hundreds of milliseconds of dead time per unit test
   suite run — and more importantly, the test doesn't actually
   *verify* the callback fires at any particular moment; it just
   verifies the callback has fired by the time the sleep ends.

The idiomatic fix for "wait for a callback to fire" in Java is a
`CountDownLatch` or `CompletableFuture<T>`:

```java
CountDownLatch delivered = new CountDownLatch(1);
atom.subscribe(snap -> delivered.countDown());
atom.set("new value");
assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
```

The test unblocks the moment the callback fires and uses the
5-second timeout only as an "obviously something is wrong" safety
net. Fast happy path, robust against CI load, clear intent.

For tests that need to inspect the delivered VALUE (not just the
fact of delivery), `CompletableFuture<T>` is cleaner:

```java
CompletableFuture<Snapshot<T>> received = new CompletableFuture<>();
atom.subscribe(received::complete);
atom.set("new value");
Snapshot<T> snap = received.get(5, TimeUnit.SECONDS);
assertThat(snap.value()).isEqualTo("new value");
```

For tests expecting multiple events, a `List<T>` + `CountDownLatch`
counted to the expected number works:

```java
List<Snapshot<T>> received = new CopyOnWriteArrayList<>();
CountDownLatch allThree = new CountDownLatch(3);
atom.subscribe(snap -> {
  received.add(snap);
  allThree.countDown();
});
atom.set("a");
atom.set("b");
atom.set("c");
assertThat(allThree.await(5, TimeUnit.SECONDS)).isTrue();
assertThat(received).hasSize(3);
```

## Scope

### Category A — Convert to latch/future

These sites use `pollDelay` to wait for a callback and then assert
what happened. Convert each to a `CountDownLatch` or
`CompletableFuture<T>` keyed off the callback, with a generous
5-second safety timeout.

- `substrate-core/.../atom/DefaultAtomTest.java:219`
- `substrate-core/.../atom/DefaultAtomTest.java:261`
- `substrate-core/.../mailbox/DefaultMailboxTest.java:100`
- `substrate-core/.../mailbox/DefaultMailboxTest.java:173`
- `substrate-core/.../subscription/FeederSupportTest.java:160`
- `substrate-core/.../memory/journal/InMemoryJournalSpiTest.java:88`
- `substrate-core/.../memory/journal/InMemoryJournalSpiTest.java:92`
- `substrate-core/.../journal/JournalSubscriptionTest.java:303`

### Category B — Tighten but keep `await().during()` for absence

These sites correctly use `await().during()` or its equivalent to
assert that an event does NOT fire within a bounded window. The
pattern is sound, but some of the windows are longer than needed
for in-memory tests:

- `substrate-core/.../subscription/BlockingBoundedHandoffTest.java:105`
  — `during(50ms)`. Consider reducing to 20ms if and only if the
  test is verifying a synchronous in-memory predicate. Do NOT
  reduce if there's any real async path involved — the window
  must be long enough for the callback to plausibly have fired if
  it were going to.

The guideline: the `during(...)` window must be >= the longest
realistic callback latency in the in-memory code path. For
synchronous mutex-style coordination, 20-50ms is plenty. For
virtual-thread-dispatched callbacks, 100ms is a safer floor.

### Category C — Leave alone (wall-clock TTL tests)

These tests wait for an actual TTL to expire. They depend on
real wall-clock time, and the `await().atMost(...)` safety net
is already robust:

- `substrate-core/.../memory/atom/InMemoryAtomSpiTest.java:161`
  — `pollDelay(1 second)` for TTL expiry
- `substrate-core/.../atom/DefaultAtomTest.java:411`
  — `pollDelay(500ms)` for TTL expiry

**Do not convert these to latches.** The TTL expiry has no
callback to listen to; it's a passive "time has passed" event.
The only way to eliminate the wall-clock wait would be to inject
a mockable `Clock` into `InMemoryAtomSpi`, which is a separate
refactor out of scope for this spec.

## Acceptance criteria

### Category A conversions

- [ ] Each of the 8 Category A sites is rewritten to use
      `CountDownLatch.await(5, TimeUnit.SECONDS)` or
      `CompletableFuture.get(5, TimeUnit.SECONDS)` instead of
      `pollDelay(...).until(() -> true)`.
- [ ] The test asserts that `latch.await(...)` returns `true` or
      that `future.get(...)` returns the expected value — do not
      silently discard the timeout.
- [ ] Every callback registration uses the latch/future's methods
      directly (`latch::countDown`, `future::complete`) rather
      than capturing into a `volatile` field.
- [ ] The 5-second safety timeout is NOT made configurable or
      shortened. It must be long enough that CI load under any
      reasonable conditions never causes a spurious failure,
      and short enough that a genuine hang surfaces as a
      recognizable test failure (not a `./mvnw verify` hang).

### Category B tightening

- [ ] `BlockingBoundedHandoffTest.java:105` — either left alone
      or reduced to a value >= the longest realistic in-memory
      callback latency. Document the reasoning in a code comment
      if reducing.
- [ ] No Category B site is converted to a latch. `during()` is
      the correct idiom for "assert absence" — latches only work
      for "assert presence."

### Category C — explicit no-op

- [ ] `InMemoryAtomSpiTest:161` and `DefaultAtomTest:411` are
      NOT modified. They remain as wall-clock TTL tests with
      `await().atMost(...)` safety nets.

### Build

- [ ] `./mvnw -pl substrate-core verify` passes locally.
- [ ] `./mvnw spotless:check` passes.
- [ ] Unit test suite wall-clock time for `substrate-core`
      decreases measurably (expected savings: ~500 ms across the
      affected tests).
- [ ] SonarCloud `java:S2925` count for `substrate-core` drops
      to match the new state (Category A sites no longer have
      `pollDelay.until(() -> true)`).
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- When a test captures multiple values from repeated callbacks,
  use a `CopyOnWriteArrayList<T>` as the accumulator and a
  `CountDownLatch(n)` as the completion signal. Do not use
  `ArrayList` — the callback runs on a different virtual thread
  and `ArrayList` is not thread-safe.
- When a test needs to assert that "the first delivery was X and
  the second was Y", prefer an `ArrayBlockingQueue<T>` so the
  test can pop in order and verify each:
  ```java
  BlockingQueue<Snapshot<T>> queue = new ArrayBlockingQueue<>(10);
  atom.subscribe(queue::offer);
  atom.set("first");
  atom.set("second");
  assertThat(queue.poll(5, SECONDS).value()).isEqualTo("first");
  assertThat(queue.poll(5, SECONDS).value()).isEqualTo("second");
  ```
- Be careful with `CompletableFuture::complete` in a subscribe
  lambda — if the callback fires more than once, the second call
  is a no-op on a completed future. For "single delivery" tests
  this is exactly what you want; for "verify exactly one delivery"
  tests, use `CompletableFuture` and add a separate assertion
  that no further values arrive (e.g., a `during()` check after).
- `await().atMost(5s).until(latch::await)` would be a cute way to
  combine latches with Awaitility. Don't do it — the direct
  `latch.await(5, SECONDS)` is clearer and shorter.
- Do NOT globally replace `await().pollDelay(...).until(() -> true)`
  via regex. Each site's conversion depends on what the test is
  actually waiting for — inspect each one before editing.
- Interrupted exceptions from `latch.await` and `future.get` must
  be handled, not swallowed. The idiomatic pattern is:
  ```java
  try {
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new AssertionError("test was interrupted", e);
  }
  ```
  or let the test method `throws InterruptedException` if JUnit's
  default handling is acceptable.

## Out of scope

- Clock injection into `InMemoryAtomSpi` / `InMemoryJournalSpi` /
  `InMemoryMailboxSpi`. That's a bigger refactor that would
  eliminate Category C wall-clock waits and is worth considering,
  but it changes SPI constructor signatures and ripples through
  auto-configuration — a separate spec.
- Backend IT tests (`*IT.java`) in other modules. This spec is
  scoped strictly to `substrate-core` unit tests.
- Thread.sleep sites in non-test code. Main-source sleeps (if
  any remain) are a different concern.
