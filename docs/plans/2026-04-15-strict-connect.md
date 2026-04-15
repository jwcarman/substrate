# Strict Connect Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Tighten `connect` on `JournalFactory`, `AtomFactory`, and `MailboxFactory` so the first operation on a handle to a nonexistent resource fails loudly with a new `*NotFoundException`, instead of silently hanging or returning empty.

**Architecture:** Add `boolean exists(String key)` to each of the three SPIs with an abstract-base default. Add a `connected` flag to each `Default*` primitive; when true, probe `exists(key)` once on the first operation (append/subscribe/get/set/touch/deliver — **not `delete`**) and throw `*NotFoundException` if absent. After one successful probe, flip the flag off so subsequent ops skip the probe. `create(...)` produces handles with `connected=false` (no probe needed). `connect(...)` produces `connected=true`. Existing `*ExpiredException` and `*CompletedException` paths handle any race where the resource disappears after the probe succeeds.

**Contract decisions (pinned):**

1. **`create` conflict semantics are name-only.** `create(name, ttl=1h)` followed by `create(name, ttl=2h)` throws `*AlreadyExistsException` purely on name collision; the TTL the second caller passed is not compared, validated, or reconciled against the existing resource. "Already exists" means "a resource exists at this name"; policy comparison is the caller's job in the catch block. Documented on `*AlreadyExistsException` javadoc and on each factory's `create` method.
2. **Probe is one-shot, then normal terminal semantics.** On a `connect`-sourced handle, the first operation probes `exists(key)` once. If present, the probe flag is cleared and every subsequent operation behaves exactly like a `create`-sourced handle would. If the resource is deleted or expires between the probe and a later operation, the caller sees `*ExpiredException` / `*CompletedException` or an empty result per the normal terminal-state contract — not `*NotFoundException`. `*NotFoundException` is an identity-time signal ("wrong name"), not an ongoing liveness check.
3. **`delete()` does not probe.** `SPI.delete` is documented as idempotent no-op and that contract is preserved on `connect`-sourced handles. Retry-safe cleanup code must not depend on which verb constructed the handle. The javadoc on each factory's `connect` method calls this out.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5, AssertJ, Mockito, Testcontainers (per backend).

**Scope:** All three primitives in this release (0.7.0). Breaking behavior change — documented in CHANGELOG.

---

## Pre-flight

Branch `strict-connect` is already checked out.

Baseline: `./mvnw -pl substrate-api,substrate-core -am verify` passes on current `main`.

---

## Phase A — API scaffolding

### Task A1: Add three `*NotFoundException` classes

**Files:**
- Create: `substrate-api/src/main/java/org/jwcarman/substrate/journal/JournalNotFoundException.java`
- Create: `substrate-api/src/main/java/org/jwcarman/substrate/atom/AtomNotFoundException.java`
- Create: `substrate-api/src/main/java/org/jwcarman/substrate/mailbox/MailboxNotFoundException.java`

**Step 1:** Model each exception on the existing sibling (e.g., `JournalAlreadyExistsException`) — same package, same constructor shape (`String name`), `extends RuntimeException`. Javadoc says "Thrown when an operation is invoked on a handle obtained from `connect(...)` but no resource exists at that name."

**Step 2:** Run `./mvnw -pl substrate-api clean compile`. Expected: PASS.

**Step 3:** Commit.

```bash
git add substrate-api/src/main/java/org/jwcarman/substrate/{journal,atom,mailbox}/*NotFoundException.java
git commit -m "Add *NotFoundException types for strict connect"
```

---

### Task A2: Update factory javadocs

**Files:**
- Modify: `substrate-api/src/main/java/org/jwcarman/substrate/journal/JournalFactory.java` (connect javadocs)
- Modify: `substrate-api/src/main/java/org/jwcarman/substrate/atom/AtomFactory.java` (connect javadocs)
- Modify: `substrate-api/src/main/java/org/jwcarman/substrate/mailbox/MailboxFactory.java` (connect javadocs)

**Step 1:** Replace the lazy-tolerant wording on each `connect` method with: "Returns a lazy handle to an existing resource. No backend I/O is performed at this call. The first operation on the returned handle (e.g., `subscribe`, `append`, `get`) will throw `*NotFoundException` if no resource exists at the given name." Add a `@throws *NotFoundException` block to `connect` method docs with the "surfaced on first operation" phrasing.

**Step 2:** `./mvnw -pl substrate-api -P release javadoc:jar -DskipTests`. Expected: PASS (release profile catches doclint).

**Step 3:** Commit.

```bash
git commit -am "Document strict-connect contract on factory javadocs"
```

---

## Phase B — SPI contract + Default\* probe

### Task B1: Add `exists(String key)` to `JournalSpi`

**Files:**
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/journal/JournalSpi.java`
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/journal/AbstractJournalSpi.java`

**Step 1:** Add abstract method:
```java
/**
 * Returns whether a live journal exists at the given key.
 *
 * <p>A journal is "live" if it was created and has not expired. Completed journals
 * within their retention window still count as existing.
 *
 * @param key the backend storage key
 * @return {@code true} if a live journal exists at this key
 */
boolean exists(String key);
```

**Step 2:** Do NOT add a default in `AbstractJournalSpi` — force every backend to think about this explicitly. The compile failures across backend modules are the TODO list for Phase C.

**Step 3:** Confirm compile failure in all journal backends: `./mvnw -pl substrate-core compile`. Expected: PASS (only the interface changed here).

**Step 4:** Commit.

```bash
git commit -am "Add JournalSpi.exists abstract method"
```

---

### Task B2: Add `exists` + `connected` flag to `DefaultJournal` (TDD)

**Files:**
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/journal/DefaultJournal.java`
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/journal/DefaultJournalFactory.java`
- Test: `substrate-core/src/test/java/org/jwcarman/substrate/core/journal/DefaultJournalTest.java` (create if absent)

**Step 1: Write failing test** — `connect_then_subscribe_on_nonexistent_journal_throws_JournalNotFoundException`. Mock `JournalSpi`: `spi.exists(key)` returns `false`. Build a `DefaultJournal` with `connected=true`. Call `journal.subscribe()`. Assert `JournalNotFoundException` is thrown.

**Step 2:** Run: `./mvnw -pl substrate-core test -Dtest=DefaultJournalTest#connect_then_subscribe_on_nonexistent_journal_throws_JournalNotFoundException`. Expected: FAIL (no probe yet).

**Step 3: Implement.** Add `private final AtomicBoolean connected;` constructor parameter. Add new constructor overload that accepts the flag; keep the old one for backward compat (delegates with `false`). Extract a `private void ensureExists()` helper that checks `connected.get()`, calls `spi.exists(key)`, throws `JournalNotFoundException(nameFromKey)` if false, then `connected.set(false)`. Call `ensureExists()` as the first line of `append`, `complete`, `subscribe()`, `subscribeAfter`, `subscribeLast`, and each callback overload. **Do not** call `ensureExists()` from `delete()` — delete preserves idempotent no-op semantics even on connected handles.

**Step 4:** Run the test. Expected: PASS.

**Step 5:** Add positive-path test — `connected_handle_passes_through_after_exists_probe`. Verify `spi.exists` called exactly once across multiple operations.

**Step 6:** Modify `DefaultJournalFactory.connect(...)` to pass `connected=true`; `create(...)` passes `connected=false`.

**Step 7:** Run: `./mvnw -pl substrate-core test -Dtest=DefaultJournalTest`. Expected: PASS.

**Step 8:** Commit.

```bash
git commit -am "Probe existence on first op from connected journal handles"
```

---

### Task B3: Repeat B1+B2 for `AtomSpi` / `DefaultAtom` / `DefaultAtomFactory`

Same shape as B1+B2. `AtomSpi.exists(String key)` — note: `AtomSpi.read` already returns `Optional<RawAtom>` which is empty when expired-or-absent, but existence for `connect`-probe purposes is strict "record present in backend," not "read returned value." Keep `exists` separate from `read`.

`DefaultAtom` probe sites: `get`, `set`, `touch`, `subscribe`, `subscribeLast` (whatever the surface is — mirror journal). **Not `delete`** — idempotent no-op preserved.

**Commit:** `Add strict-connect existence probe for atoms`

---

### Task B4: Repeat B1+B2 for `MailboxSpi` / `DefaultMailbox` / `DefaultMailboxFactory`

Same shape. `MailboxSpi.exists(String key)`. Probe sites: `deliver`, `subscribe`. **Not `delete`** — idempotent no-op preserved.

**Commit:** `Add strict-connect existence probe for mailboxes`

---

## Phase C — Journal backends

One task per backend. Each task follows the same template:

1. **Implement** `exists(String key)` in the backend SPI using a minimal read that distinguishes absent from present (see backend-specific notes below).
2. **Write two tests** in the existing backend SPI test class:
   - `exists_returns_false_for_never_created_key`
   - `exists_returns_true_for_created_key` (and, where relevant, `exists_returns_false_after_expiry`)
3. **Run** the backend's test module.
4. **Commit** with message `Implement JournalSpi.exists for <backend>`.

### Task C1: `InMemoryJournalSpi`
Backing store is a `ConcurrentHashMap`; `exists` = `map.containsKey(key)` with same expiry check the reads already perform. File: `substrate-core/src/main/java/org/jwcarman/substrate/core/memory/journal/InMemoryJournalSpi.java`.

### Task C2: `RedisJournalSpi`
`EXISTS key` via Lettuce. File: `substrate-redis/src/main/java/org/jwcarman/substrate/redis/journal/RedisJournalSpi.java`. Tests use existing Redis testcontainer setup.

### Task C3: `NatsJournalSpi`
Check KV bucket / stream existence via JetStream management API. File: `substrate-nats/.../NatsJournalSpi.java`.

### Task C4: `RabbitMqJournalSpi`
RabbitMQ streams: query stream metadata. Translate "stream does not exist" → `false`. File: `substrate-rabbitmq/.../RabbitMqJournalSpi.java`.

### Task C5: `CassandraJournalSpi`
`SELECT key FROM journals WHERE key = ? LIMIT 1`. File: `substrate-cassandra/.../CassandraJournalSpi.java`.

### Task C6: `DynamoDbJournalSpi`
`GetItem` with `ProjectionExpression` on the journal-metadata row (or equivalent existence row). File: `substrate-dynamodb/.../DynamoDbJournalSpi.java`.

### Task C7: `MongoDbJournalSpi`
`countDocuments({_id: key}, limit=1)` or presence of metadata doc. File: `substrate-mongodb/.../MongoDbJournalSpi.java`.

### Task C8: `PostgresJournalSpi`
`SELECT 1 FROM journals WHERE key = ? LIMIT 1`. File: `substrate-postgresql/.../PostgresJournalSpi.java`.

### Task C9: `HazelcastJournalSpi`
`IMap.containsKey`. File: `substrate-hazelcast/.../HazelcastJournalSpi.java`.

---

## Phase D — Atom backends

Same template as Phase C. Backends: `InMemoryAtomSpi`, `RedisAtomSpi`, `NatsAtomSpi`, `CassandraAtomSpi`, `DynamoDbAtomSpi`, `MongoDbAtomSpi`, `PostgresAtomSpi`, `HazelcastAtomSpi`, `EtcdAtomSpi`.

For several of these, `exists` can be implemented in terms of the existing `read` (return `read(key).isPresent()`), which is the simplest correct default. If the backend has a cheaper existence check, use it. Each task should pick the cheaper path where one exists.

Tasks D1–D9, one per backend. Commit template: `Implement AtomSpi.exists for <backend>`.

---

## Phase E — Mailbox backends

Same template. Backends: `InMemoryMailboxSpi`, `RedisMailboxSpi`, `NatsMailboxSpi`, `DynamoDbMailboxSpi`, `MongoDbMailboxSpi`, `PostgresMailboxSpi`, `HazelcastMailboxSpi`.

Note: `MailboxSpi.get` returns `Optional<byte[]>` which conflates "created-but-empty" with "does-not-exist." For mailboxes, `exists` MUST be implemented separately from `get` — a freshly-created mailbox with no delivery still exists.

Tasks E1–E7, one per backend. Commit template: `Implement MailboxSpi.exists for <backend>`.

---

## Phase F — End-to-end verification and release prep

### Task F1: End-to-end test per primitive against in-memory

**Files:**
- Test: `substrate-core/src/test/java/org/jwcarman/substrate/core/journal/StrictConnectEndToEndTest.java` (new)
- Test: analogous atom and mailbox end-to-end tests

**Step 1:** For each primitive, write one integration-style test that:
1. Calls `factory.connect("typo-name", Type.class)` — succeeds (no I/O).
2. Calls the first real operation — expects `*NotFoundException`.
3. Creates the resource, connects a second time, first op succeeds.

**Step 2:** Run: `./mvnw -pl substrate-core test`. Expected: PASS.

**Step 3:** Commit.

```bash
git commit -am "End-to-end tests for strict-connect failure mode"
```

---

### Task F2: Full-repo build + release-profile javadoc

**Step 1:** `./mvnw clean verify`. Expected: PASS across all backends.

**Step 2:** `./mvnw -P release javadoc:jar -DskipTests`. Expected: PASS (catches doclint surprises before release).

**Step 3:** If anything fails, fix in-place and commit per fix.

---

### Task F3: CHANGELOG entry

**Files:**
- Modify: `CHANGELOG.md`

**Step 1:** Under `## [Unreleased]`, add:

```markdown
### Breaking changes

- `JournalFactory.connect`, `AtomFactory.connect`, and `MailboxFactory.connect` now
  fail loudly when the named resource does not exist. The first operation on a
  handle returned from `connect(...)` throws `JournalNotFoundException`,
  `AtomNotFoundException`, or `MailboxNotFoundException` if no resource exists
  at that name, instead of silently returning empty reads or parked subscriptions.

  Migration: consumers that relied on the old lazy-tolerant behavior (creating
  the resource elsewhere, then connecting speculatively from another process)
  should either create-before-connect explicitly, or handle the new exception.
  Get-or-create patterns remain expressible as `try { create(...) } catch
  (AlreadyExistsException) { connect(...) }`.

### Added

- `JournalNotFoundException`, `AtomNotFoundException`, `MailboxNotFoundException`
  in `substrate-api`.
- `exists(String key)` on `JournalSpi`, `AtomSpi`, and `MailboxSpi` for backend
  implementers.
```

**Step 2:** Commit.

```bash
git commit -am "CHANGELOG: strict-connect breaking change for 0.7.0"
```

---

## Out of scope

- No changes to `*ExpiredException` or `*CompletedException` — those remain the signals for operational state, distinct from identity state.
- No new verbs (no `ensure`, no `getOrCreate`, no `adopt`). Consumers compose via try/catch.
- No changes to SPI contracts beyond adding `exists`.
- No Odyssey / Mocapi changes in this PR; those consumers update independently.

## Resolved decisions

1. `exists` returns `true` for completed-but-retained journals — "live in the backend," not "accepting writes." Completed journals serve reads and must be attachable.
2. `delete()` does NOT probe — idempotent no-op contract preserved.
3. No per-call opt-out for `exists` — the probe is one-shot and trivially cheap on every inspected backend.
