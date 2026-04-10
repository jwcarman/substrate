# Journal lifecycle completion + Mailbox connect

**Depends on: spec 019 (intentionally-leased cleanup) must be completed
first.** Spec 019 restructures `SubstrateProperties`, moves Journal and
Mailbox types into the `substrate-api` / `substrate-core` split, and
adds `MailboxFactory` as an interface. This spec builds on those
foundations.

This spec can be worked before or after specs 020, 021, 022, 023 — the
scopes don't overlap. Ralph's numeric order will pick it up as the last
breaking-change sweep.

## What to build

Finish applying the "intentionally leased" tenet to `Journal`, and add
the consumer-path `connect` method to `MailboxFactory`. Two related
themes in one spec because they touch the same two primitives.

### Journal: full lifecycle state machine

Today, `Journal` has entries (which are leased per-append) but no
journal-level lifetime. This leaves the primitive with two concrete
holes:

1. **Abandoned never-completed journals leak metadata.** If a producer
   creates a journal, appends some entries, and then crashes or forgets
   about it, the entries eventually expire but the backend's journal
   shell (Redis stream object, Postgres row bookkeeping, completion
   flag, etc.) lingers. Small orphan per abandoned journal, but the
   leased tenet exists specifically to forbid orphans.
2. **Consumers can poll abandoned journals forever.** A consumer in a
   `while (true) { cursor.poll(30s) }` loop has no way to escape an
   abandoned journal. `cursor.poll` keeps returning `Optional.empty()`
   because there's no backend signal that distinguishes "no new data
   yet" from "no new data, ever." The only "stop" signal is
   `complete()`, which a crashed producer never called.

The fix: **journals get an eager creation-time inactivity TTL**, and
`complete()` takes a retention TTL. Both are renewable in different
ways; together they give the journal a principled full-lifecycle state
machine.

### Mailbox: connect method

`MailboxFactory.create(name, type, Duration ttl)` (from spec 019) is
eager — it writes a reservation to the backend. Consumers that attach
to an existing mailbox (e.g., a load-balanced sibling process picking
up an elicitation response) need a **lazy** path that doesn't
re-create the reservation. Add `connect(name, type)` matching the
`AtomFactory.connect` pattern.

## Journal state machine

```
    JournalFactory.create(name, type, inactivityTtl)
                      │
                      ▼
                [ active ]  ─ no append for inactivityTtl ─►  [ expired ]
                      │                                           ▲
                      │ append(data, entryTtl)                    │
                      │   → resets last_append_at                 │
                      │                                           │
                      │ complete(retentionTtl)                    │
                      ▼                                           │
                [ completed ] ── retentionTtl elapses ────────────┘
                      │
                      │ append → JournalCompletedException
                      │ read   → still works until expired
                      │
                      │ delete()
                      ▼
                  (removed)
```

Three states:

- **Active.** Normal operation. `append` succeeds and resets
  `last_append_at`. Reads return entries. `complete(retentionTtl)`
  transitions to completed. The journal dies if `now >
  last_append_at + inactivityTtl` and no entries remain.
- **Completed.** `append` throws `JournalCompletedException`. Reads
  still work — consumers can replay the history. Once
  `completed_at + retentionTtl` passes, transitions to expired.
- **Expired.** Any operation (append, read, complete, touch) throws
  `JournalExpiredException`. The sweeper eventually removes the
  backend state physically.

## API changes

### `Journal<T>` — method signatures

```java
package org.jwcarman.substrate.journal;

import java.time.Duration;
import java.util.Optional;

public interface Journal<T> {

  /**
   * Append an entry to this journal. Resets the journal's inactivity
   * timer as a side effect.
   *
   * @throws JournalCompletedException if the journal has been completed
   *         (reads still work, but writes are rejected)
   * @throws JournalExpiredException if the journal has expired — either
   *         via inactivity timeout in the active state or via retention
   *         timeout in the completed state
   */
  String append(T data, Duration entryTtl);

  /**
   * Mark this journal as completed. No more appends will be accepted
   * after this call. Entries remain readable for {@code retentionTtl}
   * after completion.
   *
   * <p>Calling {@code complete} on an already-completed journal updates
   * the retention TTL — latest call wins. Use this to extend retention
   * without needing a touch-style method.
   *
   * @throws JournalExpiredException if the journal is already expired
   */
  void complete(Duration retentionTtl);

  /**
   * Remove this journal and all of its entries.
   *
   * <p>Idempotent — deleting an already-deleted or expired journal is a
   * no-op.
   */
  void delete();

  /**
   * Open a cursor that reads entries from this journal.
   *
   * @throws JournalExpiredException if the journal is expired
   */
  JournalCursor<T> read();

  /**
   * Open a cursor that reads entries strictly after the given entry id.
   *
   * @throws JournalExpiredException if the journal is expired
   */
  JournalCursor<T> readAfter(String afterId);

  /**
   * Open a cursor that reads the last {@code count} entries.
   *
   * @throws JournalExpiredException if the journal is expired
   */
  JournalCursor<T> readLast(int count);

  /** The backend-qualified key for this journal. */
  String key();
}
```

The signature change is `complete(Duration retentionTtl)` replacing the
parameter-less `complete()`. This is a breaking change. Every existing
caller must be updated.

The `append(T data)` no-TTL overload (if still present after spec 019)
remains absent. `append(T data, Duration entryTtl)` is the only write
path.

### `JournalFactory` — eager create + lazy connect

```java
package org.jwcarman.substrate.journal;

import java.time.Duration;
import org.jwcarman.codec.spi.TypeRef;

public interface JournalFactory {

  /**
   * Create a new journal.
   *
   * <p>Eager: writes a journal record to the backend with the supplied
   * inactivity TTL. The journal dies if no appends occur within
   * {@code inactivityTtl}. Each {@link Journal#append} resets the
   * inactivity clock.
   *
   * <p>The inactivity TTL should be chosen to accommodate the longest
   * legitimate gap between appends. For SSE streams: the maximum
   * acceptable silence before the stream is considered abandoned. For
   * event queues: roughly 2-5x the expected inter-message interval.
   *
   * @throws JournalAlreadyExistsException if a live journal already
   *         exists with this name
   */
  <T> Journal<T> create(String name, Class<T> type, Duration inactivityTtl);
  <T> Journal<T> create(String name, TypeRef<T> typeRef, Duration inactivityTtl);

  /**
   * Reattach to an existing journal.
   *
   * <p>Lazy: performs no backend I/O. The returned handle is a local
   * object. Existence is discovered on the first operation — any read
   * or write call throws {@link JournalExpiredException} if no live
   * journal exists at this name.
   *
   * <p>Use {@code connect} for consumer services, secondary writer
   * services, and any code path that expects the journal to already
   * exist. Use {@code create} only for the primary producer path.
   */
  <T> Journal<T> connect(String name, Class<T> type);
  <T> Journal<T> connect(String name, TypeRef<T> typeRef);
}
```

Note: a third exception type, `JournalAlreadyExistsException`, mirrors
`AtomAlreadyExistsException` for the create-collision case. Add it
alongside the other new Journal exceptions.

### `MailboxFactory` — connect method

Add to the existing `MailboxFactory` interface from spec 019:

```java
package org.jwcarman.substrate.mailbox;

import java.time.Duration;
import org.jwcarman.codec.spi.TypeRef;

public interface MailboxFactory {

  // Existing from spec 019:
  <T> Mailbox<T> create(String name, Class<T> type, Duration ttl);
  <T> Mailbox<T> create(String name, TypeRef<T> typeRef, Duration ttl);

  /**
   * Reattach to an existing mailbox.
   *
   * <p>Lazy: performs no backend I/O. The returned handle is a local
   * object. Existence is discovered on the first operation — any
   * {@code deliver} / {@code poll} call throws
   * {@link MailboxExpiredException} if no live mailbox exists at this
   * name.
   *
   * <p>Use this from consumer or secondary-writer code paths that
   * expect the mailbox to already have been created by someone else.
   */
  <T> Mailbox<T> connect(String name, Class<T> type);
  <T> Mailbox<T> connect(String name, TypeRef<T> typeRef);
}
```

### New exceptions in `substrate-api`

```java
package org.jwcarman.substrate.journal;

/**
 * Thrown when an append is attempted on a journal that has been
 * completed but not yet expired. Reads on a completed journal still
 * work until the retention TTL elapses.
 */
public class JournalCompletedException extends RuntimeException {
  public JournalCompletedException(String key) {
    super("Journal has been completed: " + key);
  }
}
```

```java
package org.jwcarman.substrate.journal;

/**
 * Thrown when an operation targets a journal that has expired —
 * either via inactivity timeout in the active state, or via retention
 * timeout in the completed state, or via explicit deletion.
 */
public class JournalExpiredException extends RuntimeException {
  public JournalExpiredException(String key) {
    super("Journal has expired or been deleted: " + key);
  }
}
```

```java
package org.jwcarman.substrate.journal;

/**
 * Thrown when {@link JournalFactory#create} is called on a name that
 * already has a live journal.
 */
public class JournalAlreadyExistsException extends RuntimeException {
  public JournalAlreadyExistsException(String key) {
    super("Journal already exists: " + key);
  }
}
```

## SPI changes

### `JournalSpi`

The SPI grows and changes substantially. New shape after this spec:

```java
package org.jwcarman.substrate.core.journal;

import java.time.Duration;
import java.util.List;
import org.jwcarman.substrate.journal.JournalAlreadyExistsException;
import org.jwcarman.substrate.journal.JournalCompletedException;
import org.jwcarman.substrate.journal.JournalExpiredException;

public interface JournalSpi {

  /**
   * Create a new journal with an inactivity TTL. Must be atomic
   * "set-if-not-exists" at the backend level.
   *
   * @throws JournalAlreadyExistsException if a live journal already
   *         exists at this key
   */
  void create(String key, Duration inactivityTtl);

  /**
   * Append an entry, resetting the journal's {@code last_append_at}
   * timestamp as part of the same atomic operation.
   *
   * @throws JournalCompletedException if the journal is completed
   * @throws JournalExpiredException if the journal is expired
   */
  String append(String key, byte[] data, Duration entryTtl);

  /**
   * Mark the journal as completed with a retention TTL. The entries
   * remain readable until {@code now + retentionTtl}; after that the
   * journal transitions to expired and reads start throwing
   * {@link JournalExpiredException}.
   *
   * <p>Calling {@code complete} on an already-completed journal
   * updates the retention TTL — latest call wins.
   *
   * @throws JournalExpiredException if the journal is already expired
   */
  void complete(String key, Duration retentionTtl);

  /**
   * Read entries strictly after the given id.
   *
   * @throws JournalExpiredException if the journal is expired
   */
  List<RawJournalEntry> readAfter(String key, String afterId);

  /**
   * Read the last {@code count} entries in chronological order.
   *
   * @throws JournalExpiredException if the journal is expired
   */
  List<RawJournalEntry> readLast(String key, int count);

  /**
   * Check whether the journal has been completed (and is therefore
   * readable but not writable). Returns false if the journal is
   * active or expired.
   */
  boolean isComplete(String key);

  /** Remove the journal and all of its entries. Idempotent. */
  void delete(String key);

  /** Backend-qualified key for the given user-facing name. */
  String journalKey(String name);

  /**
   * Physically remove expired journal records (state markers, expired
   * entries, expired completion markers) from the backend. See spec
   * 022 for the sweep contract.
   */
  int sweep(int maxToSweep);
}
```

### `MailboxSpi`

No SPI changes required. `MailboxFactory.connect` builds a
`DefaultMailbox<T>` handle without calling the SPI; existence is
discovered on the first `deliver` / `get` call, which already throws
`MailboxExpiredException` per spec 019's semantics.

## `InMemoryJournalSpi` state machine

The in-memory implementation tracks three maps:

```java
// Active journals: key → (lastAppendAt, inactivityTtl, entries)
// Plus any existing entries storage.

private record ActiveState(
    Instant lastAppendAt,
    Duration inactivityTtl,
    BoundedEntryList entries) {
  boolean isIdleExpired(Instant now) {
    return now.isAfter(lastAppendAt.plus(inactivityTtl));
  }
}

private record CompletedState(
    Instant completedAt,
    Duration retentionTtl,
    BoundedEntryList entries) {
  boolean isRetentionExpired(Instant now) {
    return now.isAfter(completedAt.plus(retentionTtl));
  }
}

private final ConcurrentMap<String, ActiveState> active = new ConcurrentHashMap<>();
private final ConcurrentMap<String, CompletedState> completed = new ConcurrentHashMap<>();
```

Or, more ergonomically, a single sealed-type state:

```java
private sealed interface State permits Active, Completed {
  boolean isDead(Instant now);
  BoundedEntryList entries();
}

private record Active(Instant lastAppendAt, Duration inactivityTtl, BoundedEntryList entries)
    implements State { ... }

private record Completed(Instant completedAt, Duration retentionTtl, BoundedEntryList entries)
    implements State { ... }

private final ConcurrentMap<String, State> store = new ConcurrentHashMap<>();
```

The sealed-type approach is cleaner. Use it.

**Behavior per method:**

- `create(key, inactivityTtl)`: atomic put-if-absent, throws
  `JournalAlreadyExistsException` on conflict.
- `append(key, data, entryTtl)`: `compute` lambda — if state is
  `Active`, update `lastAppendAt = now`, add entry; if `Completed`,
  throw `JournalCompletedException`; if dead or absent, throw
  `JournalExpiredException`.
- `complete(key, retentionTtl)`: `compute` lambda — if state is
  `Active`, transition to `Completed(now, retentionTtl, sameEntries)`;
  if `Completed`, update retention — `Completed(existing.completedAt,
  retentionTtl, entries)` (latest call wins); if dead, throw
  `JournalExpiredException`.
- `readAfter` / `readLast`: read state, throw
  `JournalExpiredException` if dead, otherwise filter the entries
  list.
- `isComplete`: return `state instanceof Completed && !state.isDead(now)`.
- `delete`: `store.remove(key)`.
- `sweep(max)`: iterate, remove entries where `state.isDead(now)`, up
  to `max`, return count.

**Entry TTL interaction:** individual entries *also* have their own
TTLs (from `append(data, entryTtl)`). The `BoundedEntryList` already
has to filter expired entries on read. When sweep runs, it can also
compact this list (remove expired entries). This doesn't change the
journal's state — the journal is still `Active` or `Completed` even
if all its entries have individually expired.

## Max TTL configuration

`SubstrateProperties` (from spec 019) gains new fields:

```java
public record JournalProperties(
    Duration maxInactivityTtl,     // cap for create(..., inactivityTtl)
    Duration maxRetentionTtl,      // cap for complete(retentionTtl)
    Duration maxEntryTtl,           // cap for append(data, entryTtl)
    SweepProperties sweep) {

  public JournalProperties {
    if (maxInactivityTtl == null) maxInactivityTtl = Duration.ofHours(24);
    if (maxRetentionTtl == null) maxRetentionTtl = Duration.ofDays(30);
    if (maxEntryTtl == null) maxEntryTtl = Duration.ofDays(7);
    if (sweep == null) sweep = SweepProperties.defaults();
  }
}
```

Defaults:

- `substrate.journal.max-inactivity-ttl = 24h` (longest legitimate
  gap between appends before a journal is considered abandoned)
- `substrate.journal.max-retention-ttl = 30d` (longest a completed
  journal can be retained)
- `substrate.journal.max-entry-ttl = 7d` (longest an individual
  entry can live — same as the existing per-entry cap)

Enforcement happens in `DefaultJournalFactory.create` (checks
`inactivityTtl`), `DefaultJournal.complete` (checks `retentionTtl`),
and `DefaultJournal.append` (checks `entryTtl`). All three throw
`IllegalArgumentException` if the passed value exceeds the max.

## Scope boundaries

### In this spec

- Journal lifecycle state machine: eager create with inactivity TTL,
  `complete(retentionTtl)` transition, expired state with
  `JournalExpiredException`.
- `JournalFactory` becomes eager for `create` and adds `connect`.
- `Journal.complete(Duration)` replaces `complete()`.
- Three new exceptions in `substrate-api`:
  `JournalCompletedException`, `JournalExpiredException`,
  `JournalAlreadyExistsException`.
- `JournalSpi.create(String, Duration)` and the updated contracts on
  `append`, `complete`, and the read methods.
- `InMemoryJournalSpi` state machine rewrite.
- Max-TTL config fields for journal inactivity and retention.
- `MailboxFactory.connect(name, type)` lazy handle method.
- Backend `JournalSpi` implementations updated (every existing
  backend journal module — Redis, Postgres, Hazelcast, Mongo,
  Dynamo, Cassandra, NATS, Rabbit — needs create/append/complete
  updates plus the state machine enforcement).
- Tests for all state transitions, including the
  "consumer-escapes-abandoned-journal" scenario.

### Not in this spec

- Journal `touch(Duration)` — the inactivity timer is renewed via
  `append` implicitly. If a primary producer legitimately needs
  "journal stays alive even when I'm not writing" (heartbeat
  scenario), they can append a lightweight heartbeat entry with a
  short entry-TTL. A dedicated `touch` method is over-engineering.
- Journal connect with no-prior-state semantics — `connect` returns a
  handle that throws on the first operation if the journal doesn't
  exist, matching the Atom convention exactly.

## Acceptance criteria

### New types in `substrate-api`

- [ ] `org.jwcarman.substrate.journal.JournalCompletedException`
- [ ] `org.jwcarman.substrate.journal.JournalExpiredException`
- [ ] `org.jwcarman.substrate.journal.JournalAlreadyExistsException`

### `Journal<T>` signature changes

- [ ] `complete()` (no args) is removed from `Journal`.
- [ ] `complete(Duration retentionTtl)` is added to `Journal`.
- [ ] `Journal.append` javadoc documents that it can throw
      `JournalCompletedException` and `JournalExpiredException`.
- [ ] Read methods (`read`, `readAfter`, `readLast`) javadoc
      documents that they can throw `JournalExpiredException`.

### `JournalFactory` changes

- [ ] `JournalFactory.create` takes a new `Duration inactivityTtl`
      parameter.
- [ ] `JournalFactory.connect(name, type)` and `connect(name, typeRef)`
      are added and perform no backend I/O.
- [ ] `DefaultJournalFactory.create` throws
      `JournalAlreadyExistsException` on collision. Verified by a
      concurrent-creation test.
- [ ] A `connect`ed handle throws `JournalExpiredException` on its
      first `append` / `read*` / `complete` call if no live journal
      exists at the key.

### `JournalSpi` changes

- [ ] `JournalSpi.create(String, Duration)` is added and must be
      atomic set-if-not-exists (same contract as `AtomSpi.create`).
- [ ] `JournalSpi.append` updates `last_append_at` atomically as part
      of the same operation.
- [ ] `JournalSpi.append` throws `JournalCompletedException` on a
      completed journal and `JournalExpiredException` on a dead
      journal.
- [ ] `JournalSpi.complete(String, Duration)` replaces the
      parameter-less `complete`.
- [ ] `JournalSpi.complete` updates retention on a second call
      (latest-wins).
- [ ] Read methods throw `JournalExpiredException` when the journal
      is dead.

### `InMemoryJournalSpi` state machine

- [ ] Uses a sealed-type `State` with `Active` and `Completed`
      variants.
- [ ] Transitions active → completed on `complete(key, ttl)`,
      preserving entries.
- [ ] `isDead(now)` returns true when: active and
      `now > lastAppendAt + inactivityTtl`, OR completed and
      `now > completedAt + retentionTtl`.
- [ ] `append` on a dead journal throws `JournalExpiredException`.
- [ ] `append` on a completed (but not expired) journal throws
      `JournalCompletedException`.
- [ ] `append` on an active journal resets `lastAppendAt` to `now`.
- [ ] `sweep(max)` removes dead journals up to `max` and returns the
      count.

### Consumer escape-hatch test

- [ ] A test creates a journal with `inactivityTtl = 200 ms`, appends
      one entry, launches a consumer in a virtual thread that loops
      on `cursor.poll(50 ms)`. Awaitility waits for the journal to
      expire (~300 ms). The consumer's next poll throws
      `JournalExpiredException`. The test asserts that the consumer's
      loop exits cleanly within ~500 ms total — demonstrating that
      consumers escape abandoned journals without needing to
      implement their own timeout logic.

### `MailboxFactory.connect`

- [ ] `MailboxFactory.connect(name, type)` and
      `connect(name, typeRef)` added to the interface.
- [ ] `DefaultMailboxFactory.connect` performs zero backend I/O.
      Verified with a mock `MailboxSpi` that fails any method call
      during the `connect` invocation.
- [ ] A `connect`ed handle throws `MailboxExpiredException` on its
      first `deliver` or `poll` call if no live mailbox exists.

### Max TTL enforcement

- [ ] `DefaultJournalFactory.create` throws
      `IllegalArgumentException` if `inactivityTtl` exceeds
      `substrate.journal.max-inactivity-ttl`.
- [ ] `DefaultJournal.complete` throws `IllegalArgumentException`
      if `retentionTtl` exceeds `substrate.journal.max-retention-ttl`.
- [ ] `DefaultJournal.append` throws `IllegalArgumentException` if
      `entryTtl` exceeds `substrate.journal.max-entry-ttl`.
- [ ] Defaults applied when properties are absent:
      `max-inactivity-ttl = 24h`, `max-retention-ttl = 30d`,
      `max-entry-ttl = 7d`.

### Backend modules

- [ ] Every backend `JournalSpi` implementation implements the new
      `create(String, Duration)` method with an atomic insert-if-not-
      exists.
- [ ] Every backend `JournalSpi.append` atomically updates
      `last_append_at` (or the backend equivalent) as part of the
      append.
- [ ] Every backend `JournalSpi.complete(String, Duration)` signature
      is updated.
- [ ] Every backend `JournalSpi` read method throws
      `JournalExpiredException` on dead journals.

### Tests

- [ ] `DefaultJournalTest` covers the full state machine: active
      with appends, active → completed transition, completed reads
      still work, completed → expired via retention timeout, active
      → expired via inactivity timeout, dead-state exceptions on
      every method.
- [ ] `DefaultJournalFactoryTest` covers eager create, lazy connect,
      collision throws, max-TTL enforcement.
- [ ] `InMemoryJournalSpiTest` covers the state machine at the SPI
      level plus the sweep behavior.
- [ ] `DefaultMailboxFactoryTest` covers the new `connect` method
      (lazy, no backend I/O, dead-mailbox exception on first op).
- [ ] The consumer-escape-hatch test (above) passes.
- [ ] All time-sensitive tests use Awaitility — no `Thread.sleep`.
- [ ] Existing tests continue to pass (after updating any callers
      of the removed `complete()` signature and the no-TTL
      `JournalFactory.create`).

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers on every new file.
- [ ] No `@SuppressWarnings` annotations introduced.

## Implementation notes

- This spec is big but mostly mechanical once the state machine is
  in place. The complicated part is `InMemoryJournalSpi`'s sealed-
  type rewrite; everything else follows from it.
- The state machine rewrite will cascade into
  `DefaultJournalCursor` because the cursor's "just in case" reads
  need to handle the new `JournalExpiredException` gracefully — the
  reader virtual thread should catch it, push a sentinel into the
  queue, and exit. Consumers see the expiration by getting a
  meaningful exception from `cursor.poll`, not silent empty returns
  forever.
- When `DefaultJournalCursor` encounters `JournalExpiredException`
  during a "just in case" read, it should propagate that exception
  up to the consumer's `cursor.poll` call rather than logging and
  retrying. The whole point of this spec is to give consumers a
  definitive "stop polling" signal.
- For the backend implementations of `append` with atomic
  `last_append_at` update:
  - **Postgres:** one `INSERT` into entries + one `UPDATE` on the
    journal metadata row, inside a transaction.
  - **Redis:** either a Lua script that does `XADD` + `HSET
    last_append_at` + `EXPIRE`, or `MULTI`/`EXEC`. Lua is cleaner.
  - **MongoDB:** `bulkWrite` with an insert + an update, or use a
    single document storing journal metadata + entries array
    (depending on how the existing Mongo backend is structured).
  - **Cassandra:** both in a single `BATCH` statement.
  - **DynamoDB:** transact-write with put on entries table + update
    on metadata table.
  - **NATS JetStream:** publish + stream metadata update, or use
    KV for metadata and JetStream for entries.
  - **Hazelcast:** `IMap.put` for entries + `IMap.put` for metadata,
    both within a single transaction.
- The "just in case" reads in `DefaultJournalCursor` (from spec 017)
  should include an `isExpired` check so the cursor can detect
  expiration even when no notifications are arriving. This avoids a
  consumer getting stuck because the notifier didn't deliver the
  expiration signal.
- Max TTL enforcement on the three separate Journal durations
  (inactivity, retention, entry) means producers can be capped on
  each axis independently. An operator who wants to prevent
  long-running journal shells can set
  `substrate.journal.max-inactivity-ttl` low without affecting the
  per-entry retention.
- `JournalAlreadyExistsException` is a runtime exception, not
  checked — matching all other substrate exceptions. Callers that
  want to do "create or connect" can catch it:
  ```java
  Journal<Session> journal;
  try {
    journal = factory.create(name, Session.class, Duration.ofHours(1));
  } catch (JournalAlreadyExistsException e) {
    journal = factory.connect(name, Session.class);
  }
  ```
  This is a documented pattern — we don't ship a dedicated
  `createOrConnect` method.
