# Mailbox subscription migration

**Depends on: spec 033 (subscription foundation) must be completed
first.** This spec migrates Mailbox from the existing `poll(Duration)`
method to the new `Subscription`-based model built on
`SingleShotHandoff`.

## What to build

Replace `Mailbox.poll(Duration)` with the new subscription model:

- Add three `subscribe` methods to the `Mailbox<T>` interface (one
  blocking + two callback variants)
- Remove `Mailbox.poll(Duration timeout)`
- Rewrite `DefaultMailbox<T>` to construct a `SingleShotHandoff<T>`
  and spin up a feeder virtual thread that reads the mailbox state
  once, pushes the delivered value (if any), and exits
- Publish a `"__DELETED__"` notification payload on `delete()` so
  subscribers detect explicit deletion immediately
- Migrate every call site that used `mailbox.poll(...)` to the new
  subscription API

Scope is strictly Mailbox. Atom and Journal are unaffected by this
spec.

## `Mailbox<T>` interface changes

```java
package org.jwcarman.substrate.mailbox;

import java.util.function.Consumer;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;

public interface Mailbox<T> {

  // ═══════════════ write (unchanged) ═══════════════
  void deliver(T value);
  void delete();

  // ═══════════════ blocking subscribe (NEW) ═══════════════

  /**
   * Subscribe to the mailbox's single delivery. If the mailbox
   * already has a delivered value when this method is called, the
   * first {@code next()} call returns it immediately; otherwise the
   * first {@code next()} blocks waiting for delivery. After the
   * single value has been consumed, subsequent {@code next()} calls
   * return {@code NextResult.Completed} (auto-transition from the
   * underlying {@code SingleShotHandoff}).
   */
  BlockingSubscription<T> subscribe();

  // ═══════════════ callback subscribe (NEW) ═══════════════

  /**
   * Callback subscribe with only an onNext handler. The handler
   * is invoked exactly once when the single delivery arrives.
   * After the handler returns, the subscription naturally
   * terminates via {@code Completed} and the feeder exits.
   */
  CallbackSubscription subscribe(Consumer<T> onNext);

  /**
   * Callback subscribe with onNext and additional lifecycle handlers.
   * {@code onComplete} fires after the delivered value is consumed
   * by the handler; {@code onExpiration} fires if the mailbox's TTL
   * elapses before delivery; {@code onDelete} fires if the mailbox
   * is explicitly deleted before delivery.
   */
  CallbackSubscription subscribe(
      Consumer<T> onNext,
      Consumer<CallbackSubscriberBuilder<T>> customizer);

  // ═══════════════ identification (unchanged) ═══════════════
  String key();
}
```

### Removed from `Mailbox`

- `Optional<T> poll(Duration timeout)` — replaced by `subscribe()` +
  `next(timeout)`.

Every call site migrates to:

```java
// Before
Optional<Response> value = mailbox.poll(Duration.ofMinutes(5));
value.ifPresent(this::process);

// After — blocking
try (BlockingSubscription<Response> sub = mailbox.subscribe()) {
  switch (sub.next(Duration.ofMinutes(5))) {
    case NextResult.Value<Response>(var v) -> process(v);
    case NextResult.Timeout<Response> t -> handleTimeout();
    case NextResult.Expired<Response> e -> handleExpired();
    case NextResult.Deleted<Response> d -> handleDeleted();
    case NextResult.Completed<Response> c -> {}  // can't happen before Value
    case NextResult.Errored<Response>(var cause) -> handleError(cause);
  }
}

// After — callback
try (CallbackSubscription sub = mailbox.subscribe(
    value -> process(value),
    b -> b.onExpiration(this::handleExpired).onDelete(this::handleDeleted)
)) {
  waitForShutdownOrCompletion();
}
```

## `DefaultMailbox<T>` rewrite

Replace the existing `poll` implementation with a `subscribe` method
that constructs a `SingleShotHandoff<T>` and spawns a feeder virtual
thread. The feeder does one essential job: get the delivered value
(if any) into the handoff, then exit.

### The Mailbox feeder thread

Because `SingleShotHandoff` auto-transitions to `Completed` after the
single value is consumed, the Mailbox feeder thread is the simplest
of the three primitives':

1. Subscribe to the notifier for the mailbox's key
2. Do a "just in case" initial read of the mailbox state
3. If the mailbox has a delivered value, `handoff.push(value)` and
   exit — the feeder is done; the handoff handles completion
   auto-transition on the consumer side
4. If the mailbox doesn't have a value yet, park on the semaphore
   waiting for a notification
5. On wake-up, re-check; push + exit if delivered, check for
   expiry/deletion otherwise
6. On `"__DELETED__"` notification, `handoff.markDeleted()` and exit
7. On TTL expiry (detected via SPI returning expired state), call
   `handoff.markExpired()` and exit

The feeder's lifetime is short — usually just until the single
delivery arrives and is pushed. For blocking subscriptions waiting
on an elicitation that never comes, the feeder sits parked on the
semaphore until the mailbox's TTL elapses or the consumer cancels
the subscription.

### Sketch

```java
public class DefaultMailbox<T> implements Mailbox<T> {

  private final MailboxSpi mailboxSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;

  public DefaultMailbox(
      MailboxSpi mailboxSpi,
      String key,
      Codec<T> codec,
      NotifierSpi notifier) {
    this.mailboxSpi = mailboxSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
  }

  @Override
  public void deliver(T value) {
    mailboxSpi.deliver(key, codec.encode(value));
    notifier.notify(key, "delivered");
  }

  @Override
  public void delete() {
    mailboxSpi.delete(key);
    notifier.notify(key, "__DELETED__");
  }

  @Override
  public BlockingSubscription<T> subscribe() {
    SingleShotHandoff<T> handoff = new SingleShotHandoff<>();
    Runnable canceller = startFeeder(handoff);
    return new DefaultBlockingSubscription<>(handoff, canceller);
  }

  @Override
  public CallbackSubscription subscribe(Consumer<T> onNext) {
    return subscribe(onNext, null);
  }

  @Override
  public CallbackSubscription subscribe(
      Consumer<T> onNext,
      Consumer<CallbackSubscriberBuilder<T>> customizer) {
    SingleShotHandoff<T> handoff = new SingleShotHandoff<>();
    Runnable canceller = startFeeder(handoff);

    DefaultCallbackSubscriberBuilder<T> builder =
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

  private Runnable startFeeder(SingleShotHandoff<T> handoff) {
    AtomicBoolean running = new AtomicBoolean(true);
    Semaphore semaphore = new Semaphore(0);

    NotifierSubscription notifierSub = notifier.subscribe((notifiedKey, payload) -> {
      if (!key.equals(notifiedKey)) return;
      if ("__DELETED__".equals(payload)) {
        handoff.markDeleted();
        running.set(false);
      }
      semaphore.release();
    });

    Thread feederThread = Thread.ofVirtual()
        .name("substrate-mailbox-feeder", 0)
        .start(() -> {
          try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {

              // Check mailbox state.
              MailboxState state = mailboxSpi.read(key);
              // Exact SPI shape depends on spec 019's mailbox contract.
              // Conceptually: returns DELIVERED(value), EMPTY_BUT_ALIVE,
              // or EXPIRED.

              switch (state) {
                case DELIVERED(byte[] bytes) -> {
                  handoff.push(codec.decode(bytes));
                  return;   // feeder's job is done — single push, exit
                }
                case EXPIRED -> {
                  handoff.markExpired();
                  return;
                }
                case EMPTY_BUT_ALIVE -> {
                  // Park on semaphore waiting for the next notification
                  // or the poll timeout. If tryAcquire returns true, we
                  // got at least one permit — there might be more stacked
                  // up from rapid notifications, so drain them. If it
                  // returns false (timeout), nothing is stacked and we
                  // skip the drain.
                  if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                    semaphore.drainPermits();
                  }
                }
              }
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
        });

    return () -> {
      running.set(false);
      feederThread.interrupt();
      notifierSub.cancel();
    };
  }

  @Override
  public String key() {
    return key;
  }
}
```

Note: the exact `MailboxSpi` read API depends on what spec 019
ended up establishing for distinguishing "mailbox exists but empty"
from "mailbox expired" — spec 019 introduced
`MailboxExpiredException` and a tri-state read. The feeder uses
whatever contract spec 019 exposed. If the `read` returns a
three-valued shape (DELIVERED, EMPTY_BUT_ALIVE, EXPIRED), the
switch above handles each case directly. If the `read` throws
`MailboxExpiredException` on dead mailbox and returns
`Optional<byte[]>` otherwise (empty = no delivery yet), the feeder
catches the exception to call `markExpired` and checks
`Optional.isPresent()` to decide between delivered and empty.
Implementer should match whichever shape spec 019 actually
established.

### The auto-completion magic

After the feeder successfully calls `handoff.push(value)` and
exits, the consumer's subscription is in an interesting state:

- The handoff has one `NextResult.Value<T>` in its slot
- The handoff's `sealed` flag is set
- The feeder thread has exited
- The notifier subscription has been cancelled (via the `finally`
  block)

The consumer hasn't been notified yet, but the value is waiting in
the handoff. On the consumer's next call to `next(timeout)`:

1. Consumer calls `sub.next(timeout)`, which calls `handoff.pull(timeout)`
2. `SingleShotHandoff.pull` sees the `Value` in the slot, returns it,
   and atomically transitions the slot to `NextResult.Completed`
3. Consumer receives the `Value` and processes it
4. Consumer's next call to `next(timeout)` returns
   `NextResult.Completed` (the auto-transitioned slot value)
5. Consumer's subscription is now inactive — `isActive()` returns
   false because `DefaultBlockingSubscription.next` flipped `done`
   on the first non-Value, non-Timeout result

Exit sequence is clean and automatic — no explicit
`markCompleted()` call is needed anywhere because
`SingleShotHandoff` handles it internally.

For callback subscriptions, the handler loop:

1. Polls the handoff, gets `Value`, invokes `onNext(value)`
2. Polls the handoff, gets `Completed`, flips `done`, invokes
   `onComplete()` if registered, exits the loop
3. The `CallbackSubscription` is now inactive

Same clean exit, driven entirely by the handoff's internal state
machine.

### `delete()` publishes `"__DELETED__"`

```java
@Override
public void delete() {
  mailboxSpi.delete(key);
  notifier.notify(key, "__DELETED__");
}
```

An active subscription's feeder thread sees the `"__DELETED__"`
payload in the notifier handler, calls `handoff.markDeleted()`,
and sets `running = false` so the feeder's loop exits on its next
iteration (which may already be parked on the semaphore — the
`semaphore.release()` at the end of the handler wakes it).

## Test migration

Every existing test that used `mailbox.poll(...)` needs a rewrite.
The pattern:

```java
// Before
Optional<Response> value = mailbox.poll(Duration.ofMinutes(5));
assertThat(value).isPresent();
assertThat(value.get()).isEqualTo(expected);

// After
try (BlockingSubscription<Response> sub = mailbox.subscribe()) {
  NextResult<Response> result = sub.next(Duration.ofMinutes(5));
  assertThat(result).isInstanceOf(NextResult.Value.class);
  Response value = ((NextResult.Value<Response>) result).value();
  assertThat(value).isEqualTo(expected);
}
```

For callback-style tests, use a `CountDownLatch`:

```java
AtomicReference<Response> captured = new AtomicReference<>();
CountDownLatch delivered = new CountDownLatch(1);
try (CallbackSubscription sub = mailbox.subscribe(value -> {
    captured.set(value);
    delivered.countDown();
})) {
  mailbox.deliver(expectedResponse);
  assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
  assertThat(captured.get()).isEqualTo(expectedResponse);
}
```

## Acceptance criteria

### `Mailbox<T>` interface

- [ ] `Mailbox.poll(Duration)` no longer exists.
- [ ] `Mailbox.subscribe()` exists, returning
      `BlockingSubscription<T>`.
- [ ] `Mailbox.subscribe(Consumer<T>)` and
      `Mailbox.subscribe(Consumer<T>, Consumer<CallbackSubscriberBuilder<T>>)`
      exist, returning `CallbackSubscription`.
- [ ] Javadoc on each subscribe method documents the one-shot
      delivery semantics and the auto-completion behavior after
      the value is consumed.

### `DefaultMailbox<T>` implementation

- [ ] `DefaultMailbox.poll(Duration)` method is deleted.
- [ ] `DefaultMailbox.subscribe(...)` methods construct a fresh
      `SingleShotHandoff<T>` per invocation.
- [ ] Each subscribe method spawns a dedicated feeder virtual
      thread via `Thread.ofVirtual().name("substrate-mailbox-feeder", 0).start(...)`.
- [ ] The feeder thread subscribes to the `NotifierSpi` for the
      mailbox's key and calls `handoff.markDeleted()` on receiving
      a `"__DELETED__"` notification payload.
- [ ] The feeder thread does an initial "just in case" read and
      pushes the delivered value (if any) into the handoff.
- [ ] The feeder thread exits cleanly after a successful push
      (single-shot semantics — no further work needed).
- [ ] The feeder thread detects expiry (via the SPI's expired
      state signal, exact form per spec 019) and calls
      `handoff.markExpired()`.
- [ ] The feeder thread catches `MailboxExpiredException` (if the
      SPI throws it) and calls `handoff.markExpired()`.
- [ ] The feeder thread catches other `RuntimeException` and
      calls `handoff.error(cause)`.
- [ ] The canceller closure interrupts the feeder thread, cancels
      the notifier subscription, and flips the running flag.
- [ ] `DefaultMailbox.delete()` publishes a notification with
      payload `"__DELETED__"` after the SPI delete succeeds.
- [ ] `DefaultMailbox.deliver(value)` continues to publish a
      "delivered" notification after the SPI write (unchanged from
      spec 017).

### Behavior — happy path

- [ ] A `subscribe()` on an already-delivered mailbox returns a
      subscription whose first `next(timeout)` call immediately
      returns `NextResult.Value` — no waiting. Verified by a test
      that delivers first, then subscribes, and asserts the first
      `next()` returns the value within milliseconds.
- [ ] A `subscribe()` on an empty mailbox blocks the first
      `next(timeout)` until delivery happens. Verified by a test
      that starts a subscription, asserts the first `next()`
      doesn't return immediately, delivers from another thread,
      and asserts the subscription returns `Value` within
      milliseconds of the delivery.
- [ ] After a successful `Value` result, the next call to
      `next(timeout)` returns `NextResult.Completed` (the
      auto-transition from `SingleShotHandoff`). Verified by a
      unit test.
- [ ] After the `Completed` result, `sub.isActive()` returns
      `false`. Verified by a unit test.
- [ ] A callback subscription's `onNext` handler fires exactly
      once after delivery; `onComplete` handler (if registered)
      fires after `onNext` returns. Verified by a test that
      captures the order of invocations with atomic references.

### Behavior — terminal states

- [ ] A mailbox whose creation-time TTL elapses before delivery
      causes active subscriptions to receive `NextResult.Expired`.
      Verified with Awaitility.
- [ ] A mailbox that is explicitly `delete()`d before delivery
      causes active subscriptions to receive `NextResult.Deleted`
      promptly (within milliseconds via the notification).
- [ ] A callback subscription's `onExpiration` handler fires if
      the mailbox expires before delivery; `onDelete` handler
      fires if the mailbox is explicitly deleted before delivery.
      Verified by separate tests for each case.
- [ ] `onNext` does NOT fire if the mailbox is expired or deleted
      before delivery. Verified by a test that asserts the
      `onNext` counter is 0 after `onExpiration` fires.

### Single-shot guarantee

- [ ] A test attempts to deliver to an already-delivered mailbox
      (which should throw `MailboxFullException` per spec 021),
      then verifies that subscribers still see only the first
      delivered value — no second `Value` result.
- [ ] A test verifies that after the consumer has received the
      `Value` result, the feeder thread has exited. Check via a
      short Awaitility poll that the feeder is no longer alive.

### Cancel semantics

- [ ] Calling `subscription.cancel()` on an active (waiting for
      delivery) subscription stops the feeder thread. Verified by
      a test that starts a subscription, waits briefly, calls
      `cancel()`, and asserts the feeder has exited within
      milliseconds.
- [ ] Cancelling a subscription that has already received the
      value is idempotent and does not cause the consumer to
      miss the `Completed` transition (although a cancelled
      subscription typically isn't polling anymore).

### Test migration

- [ ] Every existing test that called `mailbox.poll(...)` has
      been rewritten to use the new subscription API.
- [ ] No remaining references to `mailbox.poll` anywhere in the
      repo, verified by grep.
- [ ] All migrated tests use Awaitility for time-sensitive
      assertions; no `Thread.sleep` additions.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers on every modified file.
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] **Atom and Journal are not modified by this spec.** Verified
      by `git diff --name-only` asserting no files under
      `org.jwcarman.substrate.core.atom` or
      `org.jwcarman.substrate.core.journal` have been touched.
- [ ] Backend modules implementing `MailboxSpi` are not modified.

## Implementation notes

- Mailbox's feeder thread is the simplest of the three — it has
  one job: push the value once (or mark a terminal state), then
  exit. No checkpoint management (unlike Journal), no coalescing
  (unlike Atom). The short lifetime means Mailbox feeders consume
  minimal resources even in high-concurrency scenarios.
- **The feeder exits immediately after a successful push and
  cleans up its notifier subscription via the `finally` block.**
  The `SingleShotHandoff` outlives the feeder — the consumer can
  pull whenever they want, and the handoff's auto-transition from
  `Value` to `Completed` happens internally on the consumer's
  thread without any feeder involvement. This has a nice
  consequence: once a consumer has been promised a value (the
  value is sitting in the handoff's slot), no subsequent backend
  churn — late `delete()` calls, TTL expiration, SPI errors —
  can take it away. The feeder that would have noticed those
  events has already exited and cancelled its notifier
  subscription. The consumer is immune to post-delivery state
  changes on the mailbox. This is the correct behavior for a
  one-shot delivery primitive: once you've been told "here's your
  value," the value is yours.
- `SingleShotHandoff` handles the Value → Completed auto-transition
  internally. The feeder thread does NOT need to call
  `markCompleted()` after pushing the value — doing so would be
  redundant. The auto-transition happens on the consumer's first
  successful pull, not on the feeder's push.
- For a consumer waiting on an elicitation-response mailbox that
  may time out at the application level before the underlying
  mailbox TTL expires, the pattern is:
  ```java
  try (BlockingSubscription<Response> sub = mailbox.subscribe()) {
    NextResult<Response> result = sub.next(Duration.ofMinutes(5));
    // handle Value, Timeout (application-level timeout),
    // Expired (mailbox TTL elapsed), Deleted (server cancelled),
    // Completed (shouldn't happen before Value), Errored
  }
  ```
  The application's timeout (5 minutes via `next(timeout)`) is
  independent of the mailbox's creation-time TTL. If the
  application times out first, it gets `NextResult.Timeout` and
  can decide whether to keep waiting or give up.
- The "just in case" initial read in the feeder handles the
  race where a `deliver` happens between the subscription being
  registered and the feeder thread starting its loop. Without
  the initial read, the feeder would miss the notification and
  wait forever on the semaphore. With the initial read, any
  already-delivered value is picked up immediately.
- `DefaultMailbox` doesn't need any new configuration properties
  in `SubstrateProperties` — `SingleShotHandoff` has no sizing
  parameter, so there's no knob to tune.
- The `deliver` method continues to use the existing spec-017
  pattern: write to SPI, then publish a notification with a
  non-terminal payload (the existing `"delivered"` string is
  fine). The notification's payload value doesn't matter as long
  as it isn't `"__DELETED__"`; the feeder reacts by doing an SPI
  re-read regardless of payload content.
