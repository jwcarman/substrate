# Mailbox single-delivery enforcement

**Depends on: spec 019 (intentionally-leased cleanup) must be completed
first.** Spec 019 moves `Mailbox` into `substrate-api`, introduces
creation-time TTL, and adds `MailboxExpiredException`. This spec builds
on that foundation to also enforce "one and only one delivery per
mailbox."

## What to build

Enforce the `CompletableFuture`-shaped contract of `Mailbox`: a mailbox
is empty until delivered to, and once delivered, it **cannot be written
to again**. A second `deliver` call throws `MailboxFullException`.
Reads and polls are unaffected — consumers can `poll` as many times as
they like after delivery, and the value remains available until the
mailbox's TTL elapses or `delete` is called.

This change matches the established convention that substrate
primitives have crisp lifecycle states and throw exceptions when
operations target the wrong state (Atom already does this with
`AtomExpiredException` and `AtomAlreadyExistsException`; Mailbox
already does this with `MailboxExpiredException` after spec 019).

## Rationale

A `Mailbox` is explicitly modeled on `CompletableFuture` — a one-shot
value slot. The current `MailboxSpi.deliver` implementation is
write-overriding: calling `deliver` twice silently replaces the first
value with the second. That contradicts the "one-shot" framing and
creates a silent failure mode where a bug that double-delivers may
never be noticed.

Enforcing single-delivery:

- **Matches the conceptual model.** "Delivered" is a terminal state.
  Re-delivering is a semantic error, not a valid operation.
- **Surfaces bugs at the point of error** rather than letting the
  second delivery silently win.
- **Doesn't constrain legitimate use cases.** In Mocapi's elicitation
  flow, the client response is delivered exactly once. If two client
  responses arrive (due to retry logic or split-brain), exactly one
  wins and the other observes `MailboxFullException` — giving the
  caller a clear signal to handle the duplicate.
- **Plays nicely with creation-time TTL from spec 019.** The mailbox
  lifecycle is now: *created → empty for TTL → either delivered
  (sticks around until TTL or explicit delete) or expired (dead)*.
  Disallowing re-delivery makes that lifecycle crisp.

## API changes

### New exception

**`org.jwcarman.substrate.mailbox.MailboxFullException`** (in
`substrate-api`):

```java
package org.jwcarman.substrate.mailbox;

/**
 * Thrown when {@link Mailbox#deliver} is called on a mailbox that has
 * already received a delivery. Mailboxes are one-shot — a successful
 * delivery is terminal, and subsequent delivery attempts are rejected.
 */
public class MailboxFullException extends RuntimeException {
  public MailboxFullException(String key) {
    super("Mailbox already has a delivered value: " + key);
  }
}
```

### `Mailbox.deliver` contract

Javadoc on `Mailbox.deliver` (in `substrate-api`) is updated:

```java
/**
 * Deliver a value to this mailbox. A mailbox can receive exactly one
 * delivery in its lifetime — once delivered, the value is observable
 * via {@link #poll} until the mailbox's TTL elapses or {@link #delete}
 * is called.
 *
 * @throws MailboxExpiredException if the mailbox has expired or been deleted
 * @throws MailboxFullException    if a delivery has already occurred
 */
void deliver(T value);
```

### `MailboxSpi.deliver` contract

The SPI-level `deliver` method must signal the "already delivered"
state, not just the "expired" state. Update the SPI interface contract
(in
`org.jwcarman.substrate.core.mailbox.MailboxSpi`):

```java
/**
 * Deliver a value to a previously-created mailbox.
 *
 * @throws MailboxExpiredException if the mailbox no longer exists
 * @throws MailboxFullException    if the mailbox already has a value
 */
void deliver(String key, byte[] value);
```

SPI implementations must distinguish these three states on the target
key:

1. **Dead** (expired or never created) → `MailboxExpiredException`
2. **Alive, empty** (created, no delivery yet) → accept the delivery
3. **Alive, delivered** (already has a value) → `MailboxFullException`

### `InMemoryMailboxSpi` implementation

After spec 019, `InMemoryMailboxSpi` uses an `Entry(Optional<byte[]>
value, Instant expiresAt)` record. This spec updates `deliver` to
reject an already-delivered entry:

```java
@Override
public void deliver(String key, byte[] value) {
  Instant now = Instant.now();
  Entry updated = store.compute(key, (k, existing) -> {
    if (existing == null || !existing.isAlive(now)) {
      throw new MailboxExpiredException(key);
    }
    if (existing.value().isPresent()) {
      throw new MailboxFullException(key);
    }
    return new Entry(Optional.of(value), existing.expiresAt());
  });
}
```

Note: `ConcurrentHashMap.compute` propagates runtime exceptions thrown
from the remapping function, so throwing inside `compute` is safe and
atomic.

### Backend SPI implementations

Each backend `MailboxSpi` implementation must be updated to enforce
single-delivery using the backend's native conditional-write primitive:

- **Redis:** use `SET key value NX` — but the mailbox already exists
  (from `create`) so `NX` alone isn't sufficient. Use a Lua script
  that checks the "has value" flag and sets it atomically, or use a
  hash with a `HSETNX` on the `value` field.
- **PostgreSQL:** `UPDATE mailbox SET value = ? WHERE key = ? AND
  value IS NULL` — if the row count is zero, either the mailbox is
  expired (no row) or already delivered (row exists but value is
  NOT NULL). Distinguish by a follow-up existence check (or a single
  CTE).
- **DynamoDB:** `UpdateItem` with
  `ConditionExpression: attribute_not_exists(value)`.
- **MongoDB:** `updateOne` with filter `{key: ..., value: {$exists:
  false}}`; check `modifiedCount`.
- **Hazelcast:** `IMap.replace(key, expectedEmpty, delivered)` or
  equivalent CAS primitive.
- **Cassandra:** LWT (`UPDATE ... IF value = null`) — though
  Cassandra isn't required to support Mailbox per spec 020.
- **NATS KV:** use a revision-check update (update with expected
  revision from the empty-create call).

Every backend implementation must correctly throw
`MailboxFullException` on attempted re-delivery without racing — that
is, two concurrent `deliver` calls must result in exactly one success
and one `MailboxFullException`.

## Acceptance criteria

### Exception type

- [ ] `org.jwcarman.substrate.mailbox.MailboxFullException` exists in
      `substrate-api`, extends `RuntimeException`, has a single
      constructor taking the key.

### Mailbox contract

- [ ] `Mailbox.deliver` javadoc documents both `MailboxExpiredException`
      and `MailboxFullException` as possible throws.
- [ ] A successful first `deliver` followed by a second `deliver` on
      the same mailbox handle throws `MailboxFullException`. Verified
      by unit test in `DefaultMailboxTest`.
- [ ] After a `MailboxFullException`, `poll` on the mailbox still
      returns the original delivered value. Verified by unit test.
- [ ] `delete` after a successful delivery still works (no state
      confusion). Verified by unit test.

### SPI contract

- [ ] `MailboxSpi.deliver` javadoc documents both exceptions.
- [ ] `InMemoryMailboxSpi.deliver` throws `MailboxFullException` on
      second delivery and `MailboxExpiredException` on a dead mailbox,
      and never silently overwrites. Verified by unit test in
      `InMemoryMailboxSpiTest`.

### Concurrency

- [ ] A concurrent-delivery test exists that spawns N (≥ 4) virtual
      threads, each calling `deliver` with a distinct payload on the
      same mailbox key. Exactly one thread completes normally; the
      others each observe a `MailboxFullException`. The
      `poll`-returned value matches one of the attempted payloads.
      Verified in `InMemoryMailboxSpiTest` using Awaitility for
      completion detection.
- [ ] The same concurrent-delivery test runs as part of each backend
      module's integration tests, against a real backend container,
      to verify the backend's atomic-conditional-write path. (Spec
      020 consolidation should make it easy to share the test across
      backends via a base class.)

### Backend implementations

- [ ] Each backend `MailboxSpi` implementation throws
      `MailboxFullException` on re-delivery. Verified by that backend's
      integration tests.
- [ ] No backend silently overwrites a delivered mailbox.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes with ITs: `./mvnw verify`
- [ ] Apache 2.0 license headers on the new `MailboxFullException`
      file.

## Implementation notes

- The work here is small in line count but touches every backend
  `MailboxSpi` implementation. Sequence the work after spec 020
  (backend consolidation) if both specs are in flight — it's much
  easier to update 8 consolidated modules than 6 separate
  `substrate-mailbox-*` modules.
- The concurrent-delivery test is the critical acceptance criterion.
  A backend that accidentally uses last-writer-wins instead of
  atomic-conditional-write will fail this test even though a
  single-threaded test would pass. Do not skip it.
- For `PostgreSQL`, the `UPDATE ... WHERE value IS NULL` approach
  needs care to distinguish "no row" (expired) from "row exists but
  value is not null" (already delivered). A `SELECT ... FOR UPDATE`
  inside a transaction, or a CTE with `INSERT ... ON CONFLICT`, both
  work. Pick whichever reads cleaner.
- `MailboxFullException` is a `RuntimeException`, not a checked
  exception, matching the convention of other substrate exceptions
  (`AtomExpiredException`, `MailboxExpiredException`). Callers are
  not forced to catch it — the expectation is that well-written
  code never re-delivers, and the exception is there as a loud
  signal when something *has* gone wrong.
- This spec does **not** add a "try-deliver" API variant that returns
  `boolean` instead of throwing. If that pattern proves valuable
  later, add it as a follow-on spec.
