# Fix S1854 dead assignment in AbstractSingleSlotHandoff

## What to build

`AbstractSingleSlotHandoff.pull(Duration)` contains a dead
assignment that Sonar (correctly) flags as `java:S1854`:

```java
long deadlineNanos = System.nanoTime() + timeout.toNanos();
while (slot == null) {
  long remaining = deadlineNanos - System.nanoTime();
  if (remaining <= 0) return new NextResult.Timeout<>();
  try {
    remaining = notEmpty.awaitNanos(remaining);   // dead — overwritten on next iteration
  } catch (InterruptedException _) { ... }
}
```

The `remaining = notEmpty.awaitNanos(remaining);` assignment was
introduced in spec 042 to satisfy `java:S899` ("return value
ignored"). But because `remaining` is declared *inside* the while
loop, the next iteration immediately recomputes it from
`deadlineNanos`, making the assignment formally dead.

Sonar is right. The original shape satisfied S899 by appearing to
"use" the return value, but the use was cosmetic. The correct fix
is to restructure the loop so the `awaitNanos` return value
actually feeds the next loop iteration's deadline check.

## The fix

Move `remaining` out of the loop body and initialize it from
`deadlineNanos` once. Each iteration uses the previous
`awaitNanos` return value as its new deadline, which is
semantically equivalent but makes the assignment genuinely used:

```java
long deadlineNanos = System.nanoTime() + timeout.toNanos();
long remaining = deadlineNanos - System.nanoTime();
while (slot == null) {
  if (remaining <= 0) return new NextResult.Timeout<>();
  try {
    remaining = notEmpty.awaitNanos(remaining);
  } catch (InterruptedException _) {
    Thread.currentThread().interrupt();
    return new NextResult.Timeout<>();
  }
}
```

Semantic equivalence check:

- **Spurious wakeups**: `awaitNanos` returns an estimated
  remaining time (possibly positive, possibly negative). If
  positive, the next iteration waits only that much more. If
  zero or negative, the `if (remaining <= 0)` branch triggers
  the timeout return. Same as the original loop's
  `deadlineNanos - System.nanoTime()` recomputation — just
  threaded through the return value instead of re-read from the
  clock.
- **Signaled wakeups**: `slot != null`, so the while loop exits
  before `remaining` is re-checked. The assignment's value
  doesn't matter.
- **Deadline already passed on entry**: the initial
  `remaining = deadlineNanos - System.nanoTime()` computes a
  value ≤ 0, the first loop iteration returns Timeout. Same as
  before.

No behavior change, `awaitNanos` return value is now a live read,
and S1854 and S899 both stay quiet.

## Acceptance criteria

- [ ] `AbstractSingleSlotHandoff.pull(Duration)` is restructured
      so `remaining` is declared once before the while loop and
      updated from `awaitNanos`'s return value on each iteration.
- [ ] Neither `java:S1854` nor `java:S899` fires on
      `AbstractSingleSlotHandoff.java` in the next Sonar scan.
- [ ] All existing `CoalescingHandoffTest` and
      `SingleShotHandoffTest` tests pass unchanged — behavior is
      preserved.
- [ ] `./mvnw -pl substrate-core verify` passes locally.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Do NOT change the `deadlineNanos` calculation or the initial
  `remaining` computation — both stay as-is, just hoisted out of
  the loop.
- Do NOT add an explicit Awaitility test to verify the loop still
  respects the deadline. The existing handoff tests already
  exercise the pull timeout path, and adding a new timing test
  would be brittle without adding value.
- If the `catch (InterruptedException _)` clause triggers any
  other Sonar rule after the restructure, address it in the same
  PR — don't leave a new issue behind.
- The fix only touches `AbstractSingleSlotHandoff.java`. Do NOT
  attempt a similar restructure on `BlockingBoundedHandoff` — its
  `pull` uses `queue.poll(timeout, MILLISECONDS)` which doesn't
  have the same ignored-return issue.
