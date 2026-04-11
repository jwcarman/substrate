# Subscription foundation: types, handoffs, subscriptions

**Depends on: spec 018 (Atom primitive), spec 019 (intentionally-leased
cleanup), and spec 024 (Journal lifecycle + Mailbox connect) must all
be completed first.** This spec adds infrastructure; specs 034, 035,
and 036 migrate each primitive to use it.

## What to build

Add the new unified subscription machinery to `substrate-api` and
`substrate-core` **without touching any primitive interface**. After
this spec lands, substrate has:

- A `Subscription` supertype and two concrete forms
  (`BlockingSubscription<T>`, `CallbackSubscription`) in
  `substrate-api`
- A sealed `NextResult<T>` type representing every possible outcome of
  pulling from a subscription
- A `CallbackSubscriberBuilder<T>` for configuring callback-side
  handlers
- A `NextHandoff<T>` abstraction and three implementations
  (`BlockingBoundedHandoff`, `CoalescingHandoff`, `SingleShotHandoff`)
  in `substrate-core`
- Default implementations (`DefaultBlockingSubscription<T>`,
  `DefaultCallbackSubscription<T>`, `DefaultCallbackSubscriberBuilder<T>`)
  that wire the handoff to the consumer side
- `SubstrateProperties.JournalProperties` gains a nested
  `SubscriptionProperties` record with a `queueCapacity` field

**Spec 033 is purely additive.** `Atom.watch`, `Journal.read*` returning
`JournalCursor<T>`, and `Mailbox.poll` continue to exist and work
exactly as they did before. No primitive is migrated in this spec; no
existing method is removed. Spec 033 provides the building blocks so
specs 034–036 can each migrate one primitive in isolation.

## Design context (for implementers)

Every substrate primitive has a consumer side that uses the same
underlying "nudge + re-read" pattern: a feeder virtual thread
subscribes to the notifier, performs periodic "just in case" SPI
reads, and hands decoded values to consumer-side code. Today, that
pattern is wrapped in three different primitive-specific façades
(`Atom.watch`, `JournalCursor`, `Mailbox.poll`) with divergent return
types and idioms. The goal of this spec is to extract the shared
machinery into first-class types that every primitive can delegate
to.

The new machinery has three layers:

```
┌──────────────┐   push/mark       ┌────────────┐   pull        ┌──────────────┐
│  Feeder      │──────────────────►│ NextHandoff│──────────────►│  Reader      │
│  virtual     │                    │  (strategy)│                │  (blocking or│
│  thread      │                    │            │                │   callback)  │
│ (SPI-facing) │                    │            │                │              │
└──────────────┘                    └────────────┘                └──────────────┘
 primitive-specific                  strategy-specific              primitive-agnostic
 strategy-agnostic                                                  strategy-agnostic
```

Spec 033 builds the middle (the `NextHandoff` abstraction with three
implementations) and the right (the subscription types and default
implementations). The left (the feeder thread) is built per-primitive
in specs 034–036.

## New types in `substrate-api`

### `Subscription`

```java
package org.jwcarman.substrate;

/**
 * Common supertype for any running subscription to a substrate
 * primitive. Provides lifecycle inspection and cancellation.
 */
public interface Subscription {

  /**
   * True while the subscription is still capable of delivering values.
   * Becomes false after the consumer has observed a terminal outcome
   * ({@code Completed}, {@code Expired}, {@code Deleted}, {@code Errored})
   * or after {@link #cancel()} has been called.
   */
  boolean isActive();

  /**
   * Stop the subscription's feeder thread, release the notifier
   * subscription, and free internal resources. Idempotent. Does NOT
   * fire any lifecycle callbacks — cancel is user-initiated, not a
   * terminal state of the underlying primitive.
   */
  void cancel();
}
```

### `NextResult<T>`

```java
package org.jwcarman.substrate;

/**
 * Outcome of a {@link BlockingSubscription#next(java.time.Duration)}
 * call. Every possible result — value, timeout, natural completion,
 * abnormal termination, unexpected error — is a distinct sealed
 * variant so callers pattern-match exhaustively and the compiler
 * enforces that every case is handled.
 */
public sealed interface NextResult<T>
    permits NextResult.Value,
            NextResult.Timeout,
            NextResult.Completed,
            NextResult.Expired,
            NextResult.Deleted,
            NextResult.Errored {

  record Value<T>(T value) implements NextResult<T> {}
  record Timeout<T>() implements NextResult<T> {}
  record Completed<T>() implements NextResult<T> {}
  record Expired<T>() implements NextResult<T> {}
  record Deleted<T>() implements NextResult<T> {}
  record Errored<T>(Throwable cause) implements NextResult<T> {}
}
```

### `BlockingSubscription<T>`

```java
package org.jwcarman.substrate;

import java.time.Duration;

/**
 * A subscription from which the caller pulls values one at a time via
 * {@link #next(Duration)}. Each call blocks up to the given timeout
 * waiting for the next outcome. The return is a sealed
 * {@link NextResult} variant that the caller pattern-matches to
 * handle each case explicitly.
 *
 * <p>There is no no-arg {@code next()} variant that would block
 * indefinitely — every poll carries an explicit timeout. Virtual
 * threads make reasonable timeouts cheap; the absence of an
 * indefinite-wait API is intentional.
 */
public interface BlockingSubscription<T> extends Subscription {
  NextResult<T> next(Duration timeout);
}
```

### `CallbackSubscription`

```java
package org.jwcarman.substrate;

/**
 * A subscription whose outcomes are delivered via handlers registered
 * on a {@link CallbackSubscriberBuilder}. Substrate runs an internal
 * handler-loop virtual thread that polls the underlying handoff and
 * dispatches each outcome to the appropriate callback. This interface
 * has no methods beyond those inherited from {@link Subscription} —
 * all interaction happens through the registered handlers.
 */
public interface CallbackSubscription extends Subscription {}
```

### `CallbackSubscriberBuilder<T>`

```java
package org.jwcarman.substrate;

import java.util.function.Consumer;

/**
 * Configures a {@link CallbackSubscription}'s lifecycle handlers. The
 * {@code onNext} handler is provided as a positional argument to the
 * primitive's {@code subscribe(...)} method and is not part of this
 * builder — the builder covers only the optional lifecycle callbacks.
 *
 * <p>Handlers not set are simply not invoked when their corresponding
 * outcome occurs.
 */
public interface CallbackSubscriberBuilder<T> {

  /**
   * Handler for unexpected errors (NOT terminal states). Fires when
   * the feeder thread catches an exception that isn't a known
   * terminal state — backend connectivity, codec decode, driver
   * exceptions, etc.
   */
  CallbackSubscriberBuilder<T> onError(Consumer<Throwable> consumer);

  /**
   * Handler fired if the underlying primitive's TTL elapses. For Atom
   * this is the lease expiring. For Mailbox this is the creation-time
   * TTL elapsing before delivery. For Journal this is the inactivity
   * or retention TTL elapsing.
   */
  CallbackSubscriberBuilder<T> onExpiration(Runnable runnable);

  /**
   * Handler fired if the underlying primitive is explicitly deleted
   * via its {@code delete()} method (local or remote process).
   */
  CallbackSubscriberBuilder<T> onDelete(Runnable runnable);

  /**
   * Handler fired on natural completion — Journal {@code complete()}
   * followed by full drain, or Mailbox single delivery consumed.
   * Never fires for Atom.
   */
  CallbackSubscriberBuilder<T> onComplete(Runnable runnable);
}
```

## New types in `substrate-core`

### `NextHandoff<T>` — the strategy-agnostic contract

Lives in a new package `org.jwcarman.substrate.core.subscription`.

```java
package org.jwcarman.substrate.core.subscription;

import java.time.Duration;
import java.util.List;
import org.jwcarman.substrate.NextResult;

/**
 * A producer→consumer handoff point. Feeder threads push values and
 * mark terminal states; consumers (blocking or callback) pull
 * {@link NextResult} variants. Each primitive picks a concrete
 * implementation whose semantics match the primitive's needs.
 *
 * <p>Three implementations ship in the foundation:
 *
 * <ul>
 *   <li>{@link BlockingBoundedHandoff} — bounded queue, blocks feeder
 *       on full, delivers values in FIFO order. Used by Journal.
 *   <li>{@link CoalescingHandoff} — single slot, latest-wins. Feeder
 *       never blocks; consumer only ever sees the most recent value.
 *       Used by Atom.
 *   <li>{@link SingleShotHandoff} — single slot, sealed after one
 *       push. Auto-transitions to {@code Completed} after the value
 *       is consumed. Used by Mailbox.
 * </ul>
 *
 * <p>The contract is identical across strategies: producers push
 * values, consumers pull {@link NextResult}. The strategy determines
 * what happens when a producer pushes faster than the consumer pulls,
 * but the consumer code path is unchanged regardless of strategy.
 */
public interface NextHandoff<T> {

  /**
   * Push a value. Behavior on overflow or after seal varies by
   * strategy — see concrete implementation javadoc.
   */
  void push(T item);

  /** Convenience: push each item. Strategy may coalesce or drop. */
  void pushAll(List<T> items);

  /**
   * Pull up to {@code timeout} for the next outcome. Returns one of
   * the six {@link NextResult} variants. Synthesizes
   * {@link NextResult.Timeout} when the timeout elapses without an
   * item being available.
   */
  NextResult<T> pull(Duration timeout);

  /** Mark the handoff as terminally errored. */
  void error(Throwable cause);

  /** Mark the handoff as terminally completed. */
  void markCompleted();

  /** Mark the handoff as terminally expired. */
  void markExpired();

  /** Mark the handoff as terminally deleted. */
  void markDeleted();
}
```

### `BlockingBoundedHandoff<T>` — FIFO with backpressure (for Journal)

```java
package org.jwcarman.substrate.core.subscription;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.substrate.NextResult;

/**
 * A bounded FIFO handoff. Producers block on {@link #push} when the
 * queue is at capacity (natural backpressure). Consumers receive
 * values in FIFO order; terminal markers are delivered after any
 * queued values have been drained. Once marked, further pushes are
 * silently dropped.
 *
 * <p>Use for primitives where every value matters — Journal.
 */
public class BlockingBoundedHandoff<T> implements NextHandoff<T> {

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
    } catch (InterruptedException e) {
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
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new NextResult.Timeout<>();
    }
  }

  @Override public void error(Throwable cause)    { mark(new NextResult.Errored<>(cause)); }
  @Override public void markCompleted()           { mark(new NextResult.Completed<>()); }
  @Override public void markExpired()             { mark(new NextResult.Expired<>()); }
  @Override public void markDeleted()             { mark(new NextResult.Deleted<>()); }

  private void mark(NextResult<T> terminal) {
    if (marked.compareAndSet(false, true)) {
      try {
        queue.put(terminal);   // blocks briefly if queue is full
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
```

### `CoalescingHandoff<T>` — single-slot latest-wins (for Atom)

```java
package org.jwcarman.substrate.core.subscription;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.jwcarman.substrate.NextResult;

/**
 * A single-slot handoff where pushes overwrite the current value.
 * Consumers only ever see the most recent value — intermediate
 * values pushed while the consumer was slow are silently discarded.
 *
 * <p>This matches the "distributed {@code AtomicReference}" mental
 * model for {@code Atom}: only the current state is interesting, and
 * superseded historical states would be wasted work.
 *
 * <p>Producers never block on push — there is no backpressure at
 * this handoff. If a fast producer outpaces a slow consumer, the
 * producer's pushes simply overwrite each other and the consumer
 * catches up to the latest state on its next pull.
 *
 * <p>Terminal markers are sticky: once any mark method has been
 * called, the slot holds the terminal {@link NextResult} and all
 * subsequent pulls return it.
 */
public class CoalescingHandoff<T> implements NextHandoff<T> {

  private final Lock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private NextResult<T> slot;
  private boolean terminal;

  @Override
  public void push(T item) {
    lock.lock();
    try {
      if (terminal) return;
      slot = new NextResult.Value<>(item);
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void pushAll(List<T> items) {
    if (items.isEmpty()) return;
    push(items.get(items.size() - 1));   // only the most recent matters
  }

  @Override
  public NextResult<T> pull(Duration timeout) {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + timeout.toNanos();
      while (slot == null) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return new NextResult.Timeout<>();
        try {
          notEmpty.awaitNanos(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return new NextResult.Timeout<>();
        }
      }
      NextResult<T> result = slot;
      // Value slots are consumed; terminal slots stick.
      if (result instanceof NextResult.Value<T>) {
        slot = null;
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  @Override public void error(Throwable cause)    { mark(new NextResult.Errored<>(cause)); }
  @Override public void markCompleted()           { mark(new NextResult.Completed<>()); }
  @Override public void markExpired()             { mark(new NextResult.Expired<>()); }
  @Override public void markDeleted()             { mark(new NextResult.Deleted<>()); }

  private void mark(NextResult<T> terminalValue) {
    lock.lock();
    try {
      if (terminal) return;
      terminal = true;
      slot = terminalValue;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
```

### `SingleShotHandoff<T>` — one-push-and-done (for Mailbox)

```java
package org.jwcarman.substrate.core.subscription;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.jwcarman.substrate.NextResult;

/**
 * A handoff that accepts exactly one value and then seals itself.
 * After the value has been consumed via {@link #pull}, the handoff
 * automatically returns {@link NextResult.Completed} on all
 * subsequent pulls. This matches {@code Mailbox}'s one-shot delivery
 * semantic — a mailbox receives one value, and once that value has
 * been consumed, the mailbox is done.
 *
 * <p>A terminal mark fired before any push takes effect and the slot
 * holds the terminal result instead. Pushes or marks after the
 * handoff is sealed are silently dropped.
 *
 * <p>Producers never block on push.
 */
public class SingleShotHandoff<T> implements NextHandoff<T> {

  private final Lock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private NextResult<T> slot;
  private boolean sealed;

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
    push(items.get(0));   // one-shot: only the first item matters
  }

  @Override
  public NextResult<T> pull(Duration timeout) {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + timeout.toNanos();
      while (slot == null) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return new NextResult.Timeout<>();
        try {
          notEmpty.awaitNanos(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return new NextResult.Timeout<>();
        }
      }
      NextResult<T> result = slot;
      // Auto-transition to Completed after the one value is consumed.
      if (result instanceof NextResult.Value<T>) {
        slot = new NextResult.Completed<>();
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  @Override public void error(Throwable cause)    { mark(new NextResult.Errored<>(cause)); }
  @Override public void markCompleted()           { mark(new NextResult.Completed<>()); }
  @Override public void markExpired()             { mark(new NextResult.Expired<>()); }
  @Override public void markDeleted()             { mark(new NextResult.Deleted<>()); }

  private void mark(NextResult<T> terminalValue) {
    lock.lock();
    try {
      if (sealed) return;
      slot = terminalValue;
      sealed = true;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
```

### `DefaultBlockingSubscription<T>`

```java
package org.jwcarman.substrate.core.subscription;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;

public class DefaultBlockingSubscription<T> implements BlockingSubscription<T> {

  private final NextHandoff<T> handoff;
  private final Runnable canceller;
  private final AtomicBoolean done = new AtomicBoolean(false);

  public DefaultBlockingSubscription(NextHandoff<T> handoff, Runnable canceller) {
    this.handoff = handoff;
    this.canceller = canceller;
  }

  @Override
  public NextResult<T> next(Duration timeout) {
    NextResult<T> result = handoff.pull(timeout);
    // Any terminal variant flips `done`. Value and Timeout do not.
    switch (result) {
      case NextResult.Value<T> v -> {}
      case NextResult.Timeout<T> t -> {}
      default -> done.set(true);
    }
    return result;
  }

  @Override
  public boolean isActive() {
    return !done.get();
  }

  @Override
  public void cancel() {
    done.set(true);
    canceller.run();
  }
}
```

### `DefaultCallbackSubscription<T>`

```java
package org.jwcarman.substrate.core.subscription;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.NextResult;

public class DefaultCallbackSubscription<T> implements CallbackSubscription {

  private static final Log log = LogFactory.getLog(DefaultCallbackSubscription.class);
  private static final Duration HANDLER_POLL_INTERVAL = Duration.ofSeconds(1);

  private final NextHandoff<T> handoff;
  private final Runnable canceller;
  private final AtomicBoolean done = new AtomicBoolean(false);

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
    Thread.ofVirtual()
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
      NextResult<T> result = handoff.pull(HANDLER_POLL_INTERVAL);
      switch (result) {
        case NextResult.Value<T>(T value) -> {
          try {
            onNext.accept(value);
          } catch (RuntimeException e) {
            log.warn("onNext handler threw", e);
          }
        }
        case NextResult.Timeout<T> t -> {
          // keep polling
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
    }
  }

  private static void safeRun(Runnable runnable) {
    if (runnable == null) return;
    try {
      runnable.run();
    } catch (RuntimeException e) {
      LogFactory.getLog(DefaultCallbackSubscription.class)
          .warn("Lifecycle handler threw", e);
    }
  }

  private static void safeAccept(Consumer<Throwable> consumer, Throwable cause) {
    if (consumer == null) return;
    try {
      consumer.accept(cause);
    } catch (RuntimeException e) {
      LogFactory.getLog(DefaultCallbackSubscription.class)
          .warn("onError handler threw", e);
    }
  }

  @Override
  public boolean isActive() {
    return !done.get();
  }

  @Override
  public void cancel() {
    done.set(true);
    canceller.run();
  }
}
```

### `DefaultCallbackSubscriberBuilder<T>`

```java
package org.jwcarman.substrate.core.subscription;

import java.util.function.Consumer;
import org.jwcarman.substrate.CallbackSubscriberBuilder;

/**
 * Mutable builder used inside a primitive's
 * {@code subscribe(onNext, customizer)} method. The customizer lambda
 * runs synchronously during {@code subscribe}, sets handlers via this
 * builder, and the primitive then constructs a
 * {@link DefaultCallbackSubscription} with whichever handlers were
 * registered.
 */
public class DefaultCallbackSubscriberBuilder<T> implements CallbackSubscriberBuilder<T> {

  private Consumer<Throwable> onError;
  private Runnable onExpiration;
  private Runnable onDelete;
  private Runnable onComplete;

  @Override
  public CallbackSubscriberBuilder<T> onError(Consumer<Throwable> consumer) {
    this.onError = consumer;
    return this;
  }

  @Override
  public CallbackSubscriberBuilder<T> onExpiration(Runnable runnable) {
    this.onExpiration = runnable;
    return this;
  }

  @Override
  public CallbackSubscriberBuilder<T> onDelete(Runnable runnable) {
    this.onDelete = runnable;
    return this;
  }

  @Override
  public CallbackSubscriberBuilder<T> onComplete(Runnable runnable) {
    this.onComplete = runnable;
    return this;
  }

  public Consumer<Throwable> errorHandler()    { return onError; }
  public Runnable expirationHandler()          { return onExpiration; }
  public Runnable deleteHandler()              { return onDelete; }
  public Runnable completeHandler()            { return onComplete; }
}
```

## `SubstrateProperties` additions

Only `JournalProperties` gains a subscription field — Atom uses
`CoalescingHandoff` which has no capacity to configure, and Mailbox
uses `SingleShotHandoff` which also has no capacity.

```java
// In SubstrateProperties
public record JournalProperties(
    Duration maxInactivityTtl,
    Duration maxRetentionTtl,
    Duration maxEntryTtl,
    SweepProperties sweep,
    SubscriptionProperties subscription) {

  public JournalProperties {
    // existing defaults unchanged...
    if (subscription == null) subscription = new SubscriptionProperties(1024);
  }
}

public record SubscriptionProperties(int queueCapacity) {
  public SubscriptionProperties {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException(
          "queueCapacity must be positive: " + queueCapacity);
    }
  }
}
```

Default Journal subscription queue capacity: **1024** (matches the
existing `DefaultJournalCursor` default from spec 017, which this
foundation eventually replaces when spec 035 migrates Journal).

Operators can override:

```yaml
substrate:
  journal:
    subscription:
      queue-capacity: 4096
```

Atom and Mailbox properties remain unchanged by this spec — their
subscription behavior is not configurable because their chosen
strategies have no sizing parameter.

## Autoconfiguration

**No autoconfig changes in this spec.** No factory, no new beans. The
handoff classes and subscription types are instantiated directly by
each primitive's `Default*` implementation in specs 034–036. Spec 033
just puts the building blocks on the classpath.

## Scope boundary

Spec 033 does **not**:

- Modify `Atom`, `Journal`, or `Mailbox` interfaces
- Remove `Atom.watch`, `Journal.read*` cursor methods, or `Mailbox.poll`
- Delete `JournalCursor` or `DefaultJournalCursor`
- Add `subscribe()`, `onChange()`, `onEntry()`, `onDelivery()` methods
  to any primitive
- Rewrite any existing `DefaultAtom`, `DefaultJournal`, or
  `DefaultMailbox` code paths
- Modify any backend module

All of those happen in specs 034, 035, and 036 (one primitive each).

Spec 033 is complete when:

- The new types exist in `substrate-api` and `substrate-core`
- Unit tests for each of the three handoff strategies pass
- Unit tests for `DefaultBlockingSubscription` and
  `DefaultCallbackSubscription` pass (using hand-constructed handoffs)
- `SubstrateProperties.JournalProperties.subscription` exists with
  the 1024 default
- `./mvnw verify` passes — no regressions in existing primitive
  behavior

## Acceptance criteria

### New types in `substrate-api`

- [ ] `org.jwcarman.substrate.Subscription` — interface with
      `isActive()` and `cancel()`.
- [ ] `org.jwcarman.substrate.BlockingSubscription<T>` — extends
      `Subscription`, adds `NextResult<T> next(Duration timeout)`.
- [ ] `org.jwcarman.substrate.CallbackSubscription` — extends
      `Subscription`, marker only.
- [ ] `org.jwcarman.substrate.NextResult<T>` — sealed interface with
      six permitted record variants: `Value`, `Timeout`, `Completed`,
      `Expired`, `Deleted`, `Errored`.
- [ ] `org.jwcarman.substrate.CallbackSubscriberBuilder<T>` — with
      four setter methods returning `this` for chaining: `onError`,
      `onExpiration`, `onDelete`, `onComplete`.

### New types in `substrate-core`

- [ ] `org.jwcarman.substrate.core.subscription.NextHandoff<T>` —
      interface with `push`, `pushAll`, `pull`, `error`,
      `markCompleted`, `markExpired`, `markDeleted`.
- [ ] `org.jwcarman.substrate.core.subscription.BlockingBoundedHandoff<T>`
      — implementation backed by `LinkedBlockingQueue<NextResult<T>>`
      with `AtomicBoolean marked` flag.
- [ ] `org.jwcarman.substrate.core.subscription.CoalescingHandoff<T>`
      — single-slot implementation using `ReentrantLock` +
      `Condition`.
- [ ] `org.jwcarman.substrate.core.subscription.SingleShotHandoff<T>`
      — single-shot implementation with auto-transition to
      `Completed` on first pull.
- [ ] `org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription<T>`.
- [ ] `org.jwcarman.substrate.core.subscription.DefaultCallbackSubscription<T>`.
- [ ] `org.jwcarman.substrate.core.subscription.DefaultCallbackSubscriberBuilder<T>`.

### `SubstrateProperties` additions

- [ ] `JournalProperties` has a new `SubscriptionProperties
      subscription` field.
- [ ] `SubscriptionProperties` is a record with a single field
      `int queueCapacity`.
- [ ] Default capacity when absent is 1024.
- [ ] `SubscriptionProperties` constructor validates that
      `queueCapacity > 0`, throwing `IllegalArgumentException`
      otherwise.
- [ ] Overriding via `substrate.journal.subscription.queue-capacity:
      4096` in `application.yml` is picked up. Verified by an
      autoconfiguration test.

### `BlockingBoundedHandoff` tests

- [ ] Pushing a value and pulling returns `NextResult.Value`.
- [ ] Pulling with a timeout that elapses returns
      `NextResult.Timeout`.
- [ ] Pushing three values then calling `markCompleted` causes pulls
      to return `Value, Value, Value, Completed` in order.
- [ ] Calling `markCompleted` followed by `markExpired` results in
      only `Completed` being delivered (first marker wins).
- [ ] Pushing after `markCompleted` is silently dropped — verified by
      pushing a distinctive value after marking, then pulling until
      empty, asserting the distinctive value is never delivered.
- [ ] `push` blocks when the queue is full — verified by a
      capacity-1 handoff, pushing one value, starting a thread that
      pushes a second value, asserting the thread blocks until a
      consumer pulls.
- [ ] `pushAll` enqueues all items in order.

### `CoalescingHandoff` tests

- [ ] Pushing a value and pulling returns the value.
- [ ] Pushing three values in rapid succession and then pulling
      returns only the LAST value — intermediate values are
      overwritten.
- [ ] Pulling with no pushes returns `NextResult.Timeout` after the
      configured timeout.
- [ ] Pushing a value, pulling it, then pulling again returns
      `NextResult.Timeout` (the slot is empty after consumption).
- [ ] Marking any terminal state is sticky — subsequent pulls always
      return that terminal state.
- [ ] Pushing after a terminal mark is silently dropped.
- [ ] `pushAll` with three items coalesces to the last item —
      verified by a test that calls `pushAll(List.of(a, b, c))` and
      asserts the first pull returns `c`.
- [ ] Feeder thread never blocks on `push` — verified by a test that
      pushes 1000 items without any consumer pulling, asserting no
      blocking occurred.

### `SingleShotHandoff` tests

- [ ] Pushing a value and pulling returns that value.
- [ ] After pulling the value, pulling again returns
      `NextResult.Completed` — auto-transition.
- [ ] Pulling a third time continues to return `Completed` (sticky).
- [ ] Pushing a second value after the first is silently dropped —
      the handoff is sealed after the first push.
- [ ] Marking expired before any push causes pull to return
      `Expired`.
- [ ] Marking expired after a push is silently dropped — the
      delivered value takes precedence (verified by pushing, marking
      expired, then pulling and asserting `Value` comes out first,
      then `Completed`).
- [ ] `pushAll` with multiple items keeps only the first — verified
      by a test that calls `pushAll(List.of(a, b, c))` and asserts
      the first pull returns `a`, subsequent pulls return
      `Completed`.
- [ ] `push` never blocks (no backpressure for one-shot).

### `DefaultBlockingSubscription` tests

- [ ] `next(timeout)` returns a `Value` result when the underlying
      handoff has a value; `isActive()` remains true.
- [ ] `next(timeout)` returns a `Timeout` result when the handoff
      times out; `isActive()` remains true.
- [ ] `next(timeout)` returning a terminal variant flips
      `isActive()` to false — verified once per variant
      (`Completed`, `Expired`, `Deleted`, `Errored`).
- [ ] `cancel()` flips `isActive()` to false and runs the supplied
      canceller closure exactly once.
- [ ] A second `cancel()` call is idempotent and does not run the
      canceller twice.

### `DefaultCallbackSubscription` tests

- [ ] Constructing with only `onNext` set invokes the handler for
      each `Value` result pushed into the handoff.
- [ ] An exception thrown from `onNext` is caught and logged at
      WARN; subsequent values are still delivered (verified by
      pushing two values, first handler throws, second succeeds).
- [ ] `onCompleted` fires exactly once when the handoff is marked
      completed; the handler loop exits.
- [ ] `onExpiration` fires exactly once when the handoff is marked
      expired; the handler loop exits.
- [ ] `onDelete` fires exactly once when the handoff is marked
      deleted; the handler loop exits.
- [ ] `onError` fires exactly once with the captured cause when the
      handoff is marked errored; the handler loop exits.
- [ ] Callbacks that aren't registered are silently ignored when
      their corresponding terminal state fires — no NPE.
- [ ] `cancel()` stops the handler loop within one poll interval
      (~1 second).

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers on every new file.
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] **Zero changes to primitive interfaces** — `Atom.watch`,
      `Journal.read*` cursor methods, and `Mailbox.poll` all still
      exist and work. Verified by an automated check that these
      method signatures match their pre-spec-033 state.
- [ ] Zero changes to backend module code — verified by running
      `git diff --name-only HEAD~1` and asserting no files under
      `substrate-{redis,postgresql,hazelcast,mongodb,dynamodb,cassandra,nats,rabbitmq,sns}/`
      are modified.

## Implementation notes

- The three handoff implementations share a common pattern
  (lock + single slot, or blocking queue + marked flag) but do not
  share a base class. Each is small enough (~60-80 lines) that
  extracting a common superclass would obscure more than it reveals.
- `CoalescingHandoff` uses `ReentrantLock` + `Condition` rather than
  a `BlockingQueue` because its behavior is different from a queue:
  overwrites, not FIFO. The lock-based approach is the simplest
  correct implementation.
- `SingleShotHandoff` has the same lock-based structure as
  `CoalescingHandoff` but with `sealed` semantics instead of
  overwrite. The two classes look similar but have distinct
  invariants and shouldn't be merged.
- **Backpressure applies only to `BlockingBoundedHandoff`.** The
  other two strategies never block on push, which is correct:
  coalescing overwrites (no backpressure needed), and single-shot
  seals after one push (no backpressure possible).
- Both blocking and callback subscriptions wrap the same
  `NextHandoff<T>` — the difference is who drives the poll loop.
  The blocking subscription relies on the caller's own thread; the
  callback subscription owns a virtual thread that polls on the
  user's behalf.
- The callback subscription's handler-loop poll interval is 1
  second, hardcoded. This is short enough to detect terminal state
  changes promptly and long enough to avoid excessive wake-ups on
  idle subscriptions. If users ever need to tune it, we can expose a
  property in a follow-on spec.
- The `canceller` argument to `DefaultBlockingSubscription` and
  `DefaultCallbackSubscription` is a primitive-specific cleanup hook
  that each primitive provides when constructing the subscription.
  Typical contents: interrupt the feeder thread, cancel the notifier
  subscription, release any backend-specific resources. Specs 034,
  035, and 036 show concrete examples for each primitive.
- No `NextHandoffFactory` is introduced — primitives construct the
  appropriate handoff directly via `new CoalescingHandoff<>()`,
  `new BlockingBoundedHandoff<>(capacity)`, or `new SingleShotHandoff<>()`.
  If a future use case needs swappable handoffs (observability
  wrapper, metrics hook), a factory can be introduced as an additive
  refactor in a follow-on spec.
- Tests of the handoff implementations should use Awaitility for any
  time-sensitive assertions (no `Thread.sleep`). The only exception
  is the `push-blocks-on-full` test for `BlockingBoundedHandoff`,
  which needs to verify that a push IS blocking — use
  `assertTimeout` or a latch-based pattern.
