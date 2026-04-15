# Handoff simplification design

Date: 2026-04-15

## Motivation

The current `NextHandoff` hierarchy encodes per-primitive delivery
semantics inside the rendezvous layer. Today we have five classes:

- `NextHandoff` (interface)
- `AbstractHandoff` (folds four terminal-mark methods into one `mark` hook)
- `AbstractSingleSlotHandoff` (lock + condition + slot + `sealed` flag)
- `CoalescingHandoff` (Atom)
- `SingleShotHandoff` (Mailbox)
- `BlockingBoundedHandoff` (Journal)

The single-slot impls mix two concerns in one field: pending `Value`s and
terminal markers share `slot`, so `pull` must peek at the result kind to
decide whether to consume or retain it. `markCancelled` needs a special
case so it doesn't overwrite a pending `Value`. The bounded-queue impl
puts terminals into the queue itself, which causes the "Cancelled marker
dropped: queue full" edge case.

## Key insight

Delivery-guarantee semantics ("at-most-once", "deliver on change",
"deliver every entry") are properties *of the primitive*, not of the
rendezvous buffer. Moving them into each primitive's feeder leaves the
handoff as a dumb rendezvous with one job: park the reader until a value
or terminal is available.

## Target model

### Handoff contract

```java
interface Handoff<T> {
  void deliver(T value);
  NextResult<T> poll(Duration timeout);
  void markCompleted();
  void markExpired();
  void markDeleted();
  void error(Throwable cause);
  void markCancelled();
}
```

`pushAll` is removed — batching is a feeder concern.

### Pull semantics

Terminals are sticky; values are consumed once. Block only when both
buffer and terminal are empty.

```
poll(timeout):
  while true:
    v = takeValue()            // non-null once, then null
    if v != null:      return Value(v)
    if terminal != null: return terminal     // sticky, never cleared
    if !await(remaining): return Timeout
```

Consequences:
- Repeated `poll` after a terminal returns that terminal every time,
  without ever blocking.
- A pending `Value` is always drained before the terminal is observed,
  even if the terminal arrived while a value was still in the buffer.
- `markCancelled` is just `mark(Cancelled)` with no special case.

### Two implementations

**`SingleSlotHandoff<T>`** — used by Atom and Mailbox.

- `value` field (latest-wins, consumed on read)
- sticky `terminal` field
- lock + condition for wakeup
- `deliver(T)` overwrites `value` unconditionally and signals

**`BoundedQueueHandoff<T>`** — used by Journal.

- `BlockingQueue<T>` for values (preserves `put`-based backpressure)
- sticky `terminal` field
- `poll` drains the queue first, then checks terminal, then blocks on
  `queue.poll(timeout)`
- when a terminal arrives with an empty queue, a dedicated
  lock/condition (or equivalent signal) wakes any parked consumer so it
  re-enters the loop and observes `terminal != null`

### Feeder responsibilities

Delivery guarantees move into each primitive's feeder:

- **Atom feeder** — tracks last-delivered token; calls `deliver` only
  when the token actually changes. Latest-wins in the slot is
  semantically safe because the feeder never pushes duplicates.
- **Mailbox feeder** — calls `deliver` exactly once on first
  observation, then fires the appropriate terminal marker and exits. The
  handoff never sees a second delivery.
- **Journal feeder** — calls `deliver` for every entry in order.
  Backpressure comes from `BlockingQueue.put` blocking the feeder when
  the consumer falls behind.

## What goes away

- `AbstractHandoff`, `AbstractSingleSlotHandoff`, `CoalescingHandoff`,
  `SingleShotHandoff`, `BlockingBoundedHandoff` (5 classes).
- The `sealed` boolean.
- The `Value`-vs-terminal peek in `pull`.
- The `markCancelled` special case that avoids overwriting a pending
  `Value`.
- The "Cancelled marker dropped: queue full" edge case — terminals no
  longer contend with values for queue capacity.
- `pushAll` on the handoff.

## What to verify during implementation

- No existing feeder already does the dedup/write-once check such that
  the handoff-side version was pure duplication — if so, the handoff
  version just gets deleted without feeder changes.
- No test depends on terminal ordering that breaks once terminals live
  in a separate field (current semantics: pending values drain before
  terminal, which the new model preserves).
- The wake-on-terminal path for the bounded-queue case doesn't
  reintroduce a race where a consumer parked in `queue.poll` misses a
  terminal signal published just before parking.

## Net result

5 handoff classes collapse to 2. No inheritance. Each primitive's
delivery contract is readable in one place (its feeder) instead of
smeared across a feeder and a handoff subclass.
