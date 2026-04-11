# Extract shared scaffolding from the three `NextHandoff` implementations

## What to build

SonarCloud flags the three handoff classes with high duplication on
new code:

| File | Duplicated lines (new code) |
|---|---|
| `CoalescingHandoff.java` | 40.4% |
| `SingleShotHandoff.java` | 40.0% |
| `BlockingBoundedHandoff.java` | 17.8% |

This is not a false positive. The three classes were introduced in
spec 033 as "three variations on a single strategy," and their shared
scaffolding has been verbatim-duplicated ever since. The cleanup is
earned — 40% is enough that the abstraction pays for itself. Extract
a two-level hierarchy:

```
NextHandoff<T>                         (interface)
  └ AbstractHandoff<T>                 (shared terminal-mark dispatch)
      ├ AbstractSingleSlotHandoff<T>   (shared lock/condition/slot/pull loop)
      │   ├ CoalescingHandoff<T>
      │   └ SingleShotHandoff<T>
      └ BlockingBoundedHandoff<T>      (keeps its queue-based impl)
```

The two abstract classes capture genuinely shared structure.
`BlockingBoundedHandoff` is structurally different (uses a bounded
`LinkedBlockingQueue` instead of a slot+lock+condition) and can't
share the pull loop, but it still benefits from the terminal-mark
dispatch in `AbstractHandoff`.

## What's duplicated today

### Across all three (shared by `AbstractHandoff`)

The four terminal-mark methods are verbatim in all three files:

```java
@Override public void error(Throwable cause)  { mark(new NextResult.Errored<>(cause)); }
@Override public void markCompleted()         { mark(new NextResult.Completed<>()); }
@Override public void markExpired()           { mark(new NextResult.Expired<>()); }
@Override public void markDeleted()           { mark(new NextResult.Deleted<>()); }
```

Each handoff has its own `private void mark(NextResult<T>)` with
class-specific locking. By lifting the four public methods into
`AbstractHandoff` and declaring `mark` as a protected abstract
method, we remove ~16 lines of duplication per class.

### Across `CoalescingHandoff` and `SingleShotHandoff` (shared by `AbstractSingleSlotHandoff`)

Both classes have:

- `private final Lock lock = new ReentrantLock();`
- `private final Condition notEmpty = lock.newCondition();`
- `private NextResult<T> slot;`
- `private boolean sealed;` (called `terminal` in `CoalescingHandoff`
  today — same meaning: "no more live pushes will be accepted")
- An identical `pull(Duration)` loop structure with deadline-based
  `awaitNanos`
- An identical `mark(NextResult<T>)` method that sets `sealed`,
  sets `slot`, and signals the condition

They differ in exactly three small ways:

1. **`push(T)`** — `CoalescingHandoff` never sets `sealed` (it keeps
   accepting pushes); `SingleShotHandoff` sets `sealed = true` after
   the first push.
2. **Post-consume slot state in `pull`** — `CoalescingHandoff` sets
   `slot = null` so the next push can land;
   `SingleShotHandoff` sets `slot = new NextResult.Completed<>()`
   so the next pull returns `Completed`.
3. **`pushAll(List<T>)`** — `CoalescingHandoff` forwards the *last*
   item; `SingleShotHandoff` forwards the *first*.

Capture (1) by letting each subclass implement its own `push`.
Capture (2) via a protected abstract hook
`protected abstract NextResult<T> consumeSlot();` called from the
shared `pull` after a Value is consumed. Capture (3) by letting
each subclass override `pushAll`.

## The extraction

### `AbstractHandoff<T>`

```java
public abstract class AbstractHandoff<T> implements NextHandoff<T> {

  @Override
  public final void error(Throwable cause) {
    mark(new NextResult.Errored<>(cause));
  }

  @Override
  public final void markCompleted() {
    mark(new NextResult.Completed<>());
  }

  @Override
  public final void markExpired() {
    mark(new NextResult.Expired<>());
  }

  @Override
  public final void markDeleted() {
    mark(new NextResult.Deleted<>());
  }

  /**
   * Transition the handoff to a terminal state. Called by the four public
   * {@code error}/{@code mark*} methods above. Subclasses decide how to
   * persist the terminal value and wake any waiting consumer.
   *
   * <p>Implementations must be idempotent: a second mark must be a no-op.
   */
  protected abstract void mark(NextResult<T> terminal);
}
```

The four public methods are `final` to prevent subclasses from
overriding the dispatch — the only customization point is the
`mark` hook.

### `AbstractSingleSlotHandoff<T>`

```java
public abstract class AbstractSingleSlotHandoff<T> extends AbstractHandoff<T> {

  protected final Lock lock = new ReentrantLock();
  protected final Condition notEmpty = lock.newCondition();
  protected NextResult<T> slot;
  protected boolean sealed;

  @Override
  public final NextResult<T> pull(Duration timeout) {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + timeout.toNanos();
      while (slot == null) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return new NextResult.Timeout<>();
        try {
          remaining = notEmpty.awaitNanos(remaining);
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          return new NextResult.Timeout<>();
        }
      }
      NextResult<T> result = slot;
      if (result instanceof NextResult.Value<T>) {
        slot = consumeSlot();
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected final void mark(NextResult<T> terminal) {
    lock.lock();
    try {
      if (sealed) return;
      sealed = true;
      slot = terminal;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the value to place in the slot after a Value result has been
   * consumed by {@link #pull pull}. {@code CoalescingHandoff} returns
   * {@code null} to allow the next push to land; {@code SingleShotHandoff}
   * returns {@code new NextResult.Completed<>()} to seal the handoff.
   */
  protected abstract NextResult<T> consumeSlot();
}
```

### `CoalescingHandoff<T>` (after refactor)

```java
public class CoalescingHandoff<T> extends AbstractSingleSlotHandoff<T> {

  @Override
  public void push(T item) {
    lock.lock();
    try {
      if (sealed) return;
      slot = new NextResult.Value<>(item);
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void pushAll(List<T> items) {
    if (items.isEmpty()) return;
    push(items.get(items.size() - 1));
  }

  @Override
  protected NextResult<T> consumeSlot() {
    return null;
  }
}
```

Roughly 35 lines down from 113 today. All the locking/scaffolding
lives in the parent.

### `SingleShotHandoff<T>` (after refactor)

```java
public class SingleShotHandoff<T> extends AbstractSingleSlotHandoff<T> {

  @Override
  public void push(T item) {
    lock.lock();
    try {
      if (sealed) return;
      slot = new NextResult.Value<>(item);
      sealed = true;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void pushAll(List<T> items) {
    if (items.isEmpty()) return;
    push(items.get(0));
  }

  @Override
  protected NextResult<T> consumeSlot() {
    return new NextResult.Completed<>();
  }
}
```

Roughly 35 lines down from 115.

### `BlockingBoundedHandoff<T>` (after refactor)

Extend `AbstractHandoff<T>` directly (it doesn't fit the single-slot
pattern). The four terminal methods come from the parent; the
private `mark` becomes `protected` with the same queue-backed
implementation.

```java
public class BlockingBoundedHandoff<T> extends AbstractHandoff<T> {

  private final BlockingQueue<NextResult<T>> queue;
  private final AtomicBoolean marked = new AtomicBoolean(false);

  public BlockingBoundedHandoff(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.queue = new LinkedBlockingQueue<>(capacity);
  }

  @Override
  public void push(T item) {
    if (marked.get()) return;
    try {
      queue.put(new NextResult.Value<>(item));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void pushAll(List<T> items) {
    for (T item : items) push(item);
  }

  @Override
  public NextResult<T> pull(Duration timeout) {
    try {
      NextResult<T> r = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return r != null ? r : new NextResult.Timeout<>();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return new NextResult.Timeout<>();
    }
  }

  @Override
  protected void mark(NextResult<T> terminal) {
    if (marked.compareAndSet(false, true)) {
      try {
        queue.put(terminal);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
```

Saves the 16 lines of terminal-method duplication. Queue-based
semantics preserved exactly.

## Acceptance criteria

### New classes

- [ ] `org.jwcarman.substrate.core.subscription.AbstractHandoff<T>`
      exists, is `public abstract`, implements `NextHandoff<T>`, and
      provides `final` implementations of `error`, `markCompleted`,
      `markExpired`, `markDeleted` that all delegate to
      `protected abstract void mark(NextResult<T>)`.
- [ ] `org.jwcarman.substrate.core.subscription.AbstractSingleSlotHandoff<T>`
      exists, is `public abstract`, extends `AbstractHandoff<T>`,
      owns `lock`/`notEmpty`/`slot`/`sealed` as protected fields,
      provides a `final` `pull(Duration)` implementation matching the
      existing Coalescing/SingleShot loop, provides a `final`
      `mark(NextResult<T>)` implementation, and declares
      `protected abstract NextResult<T> consumeSlot();`.
- [ ] Both new classes have class-level Javadoc explaining their
      role in the hierarchy.

### Refactored classes

- [ ] `CoalescingHandoff<T>` extends `AbstractSingleSlotHandoff<T>`
      and only contains `push`, `pushAll`, `consumeSlot`. No direct
      lock/condition/slot declarations remain.
- [ ] `SingleShotHandoff<T>` extends `AbstractSingleSlotHandoff<T>`
      and only contains `push`, `pushAll`, `consumeSlot`.
- [ ] `BlockingBoundedHandoff<T>` extends `AbstractHandoff<T>`;
      its `mark` is now `protected` (was private); it no longer
      declares the four terminal-mark methods itself.

### Semantics preserved

- [ ] `CoalescingHandoff` still discards earlier pushes on overwrite
      and allows the next push to land after a consumer takes the
      current value.
- [ ] `SingleShotHandoff` still accepts exactly one push, still
      auto-transitions the slot to `Completed` after the first
      consumer reads the value.
- [ ] `BlockingBoundedHandoff` still blocks on push when the queue
      is full, still delivers values FIFO, still wakes the consumer
      on terminal transitions.
- [ ] Idempotency of `mark` is preserved in all three: a second
      terminal call is a no-op.
- [ ] `pushAll` semantics preserved exactly: Coalescing forwards the
      last item, SingleShot forwards the first, Bounded forwards
      all items.

### Tests

- [ ] All existing `CoalescingHandoffTest`, `SingleShotHandoffTest`,
      and `BlockingBoundedHandoffTest` tests pass unchanged.
- [ ] No new tests are required for the abstract classes — they
      are exercised entirely through the three concrete subclasses.
      However, if any concrete test case accidentally now tests
      behavior that properly belongs in the base class, that's
      fine; no need to move it.

### Build and Sonar

- [ ] `./mvnw -pl substrate-core verify` passes locally.
- [ ] `./mvnw spotless:check` passes.
- [ ] SonarCloud duplication percentages on the next scan drop
      substantially for all three handoff files. Target:
      `CoalescingHandoff.java` and `SingleShotHandoff.java` drop
      below 10% duplicated lines; `BlockingBoundedHandoff.java`
      drops below 5%.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- The `sealed` field name is a rename in `CoalescingHandoff` (was
  `terminal`). Both names describe "no more live pushes." `sealed`
  is the shared name in the base class, so Coalescing now uses it
  too.
- `AbstractSingleSlotHandoff`'s `lock`/`notEmpty`/`slot`/`sealed`
  are `protected` (not private) so subclasses' `push` methods can
  lock and manipulate the slot directly. This is deliberate
  inheritance-with-encapsulation — the protected fields are the
  contract between base and subclasses.
- Do NOT try to factor `push` into the base class via a template
  method. The two subclasses' push methods are small and the
  semantic differences (sealing after one push vs. never sealing)
  are load-bearing. A template method that took a "sealAfterPush"
  boolean would be worse than the current inheritance split.
- Do NOT move `BlockingBoundedHandoff` into the single-slot
  hierarchy by faking a slot. Its semantics (bounded FIFO with
  backpressure) are genuinely different. Sharing
  `AbstractHandoff` is enough.
- After the refactor, `CoalescingHandoff` and `SingleShotHandoff`
  should each be ~35-40 lines including imports and Javadoc. If
  either is still substantially larger, something has been missed.

## Out of scope

- Changing the `NextHandoff<T>` interface signature or semantics.
- Introducing new handoff strategies. The three that exist are
  the three the project needs.
- Moving any of the handoff classes to a different package.
