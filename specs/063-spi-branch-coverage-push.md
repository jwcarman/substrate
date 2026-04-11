# Backend SPI branch coverage push

## What to build

After specs 057, 058, and the auto-config work in 061/062, the
remaining coverage debt is concentrated in **branch coverage of
backend SPI classes**. The line coverage on these files is
mostly above 90%, but their conditional branches (lifecycle state
checks, error paths, idempotency guards) are unevenly exercised.

| File | Branch% | Uncov branches |
|---|---|---|
| `substrate-redis/.../journal/RedisJournalSpi.java` | 60.0% | 8 |
| `substrate-nats/.../journal/NatsJournalSpi.java` | 69.2% | 8 |
| `substrate-cassandra/.../atom/CassandraAtomSpi.java` | 70.0% | 6 |
| `substrate-dynamodb/.../journal/DynamoDbJournalSpi.java` | 72.2% | 10 |
| `substrate-dynamodb/.../mailbox/DynamoDbMailboxSpi.java` | 75.0% | 3 |
| `substrate-mongodb/.../journal/MongoDbJournalSpi.java` | 81.8% | 4 |
| `substrate-core/.../subscription/FeederSupport.java` | 81.3% | 3 |
| `substrate-hazelcast/.../atom/AtomEntry.java` | 83.3% | 1 |

Total: ~43 uncovered branches across 8 files.

## What's typically uncovered

Branches in journal SPIs cluster around:

- **Lifecycle guards**: `if (isComplete(key)) throw JournalCompletedException`
  — happy path tested, throw path may not be.
- **Expiry checks**: `if (entry.isExpired()) ... else ...`
  — both arms need a test, often only one is exercised.
- **Idempotent operations**: `markComplete` called twice — second
  call should be a no-op, but may not be tested.
- **Empty / boundary results**: `readAfter(key, lastId)` when
  the result is empty.

Branches in atom SPIs cluster around:

- **CAS / LWT branches**: `if (applied) ... else ...` for
  conditional updates. The "not applied" branch (concurrent
  modification, expired) is often missed.
- **TTL touch on absent key**: separate from "TTL touch on
  expired key".

Branches in `FeederSupport`:

- The `__DELETED__` notification path
- The interrupt-during-step path
- The `catch (RuntimeException)` → `handoff.error(e)` path

## Workflow

For each module:

1. Run `./mvnw -pl substrate-<module> verify`.
2. Open `substrate-<module>/target/site/jacoco/index.html`.
3. Drill into each listed class.
4. JaCoCo highlights covered branches in green, uncovered
   branches in red, partially-covered branches in yellow.
5. For each red/yellow branch, write a test that exercises the
   uncovered side.
6. Re-run verify and re-check JaCoCo until branch coverage
   ≥ 90% on each file.

## Acceptance criteria

For each of the 8 files:

- [ ] Branch coverage ≥ 90% on the next Sonar scan, OR the
      residual uncovered branches are documented as defensive
      guards that can't be exercised through the public API.
- [ ] New tests use the module's existing test style (unit
      tests where possible, ITs via testcontainers when the
      branch requires real backend behavior).
- [ ] No new `Thread.sleep` or unnecessary `pollDelay` calls.
      Use latches or synchronous coordination per spec 048.
- [ ] No production code changes — tests only. If a branch
      can't be reached through the public API and is genuinely
      defensive, leave it and document in the test file.

### Project-level

- [ ] After this spec lands, project-wide branch coverage rises
      from 87.5% to ≥ 92%.
- [ ] `./mvnw verify` passes from the root.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Recommended order

Work module-by-module to keep context local:

1. `substrate-core` — `FeederSupport` (3 branches, all in
   subscription scaffolding I know well).
2. `substrate-redis` — `RedisJournalSpi` (8 branches,
   single file, single SPI).
3. `substrate-nats` — `NatsJournalSpi` (8 branches).
4. `substrate-dynamodb` — `DynamoDbJournalSpi` (10) +
   `DynamoDbMailboxSpi` (3). Same module.
5. `substrate-cassandra` — `CassandraAtomSpi` (6).
6. `substrate-mongodb` — `MongoDbJournalSpi` (4).
7. `substrate-hazelcast` — `AtomEntry` (1, trivial).

Run `./mvnw -pl <module> verify` after each module.

## Implementation notes

- **Drive from JaCoCo, not from this spec's text.** The branch
  counts and red lines change as other specs land. The
  important thing is to get each file's branch coverage above
  90%, not to fix specific line numbers.
- For lifecycle/idempotency tests on journal SPIs: write small
  unit tests where possible (using the in-memory SPI) so they
  can run in surefire instead of failsafe. Backend ITs are for
  testing actual backend behavior, not for branch coverage.
- For CAS/LWT branches in atom SPIs: a "concurrent
  modification" test using two SPI clients hitting the same
  key from two threads (or one thread doing two sequential
  operations with stale state) usually exercises the
  not-applied branch.
- For `FeederSupport`'s catch branch: the existing
  `FeederSupportTest` already has a "step throws RuntimeException"
  test. If JaCoCo says the catch branch is uncovered, look at
  what's *partially* covered — likely the
  `Thread.currentThread().interrupt()` line in the
  `InterruptedException` arm.
- A defensive `if (impossible) throw` guard that can't be
  reached through the public API doesn't need to be removed —
  document it in a test comment and accept the residual
  uncovered branch. Better to have a guard that catches a
  future bug than to delete it for coverage.

## Out of scope

- Refactoring SPI implementations to remove defensive guards.
- Touching files already at 100% branch coverage.
- Coverage of `*AutoConfiguration.java` files (covered by
  spec 061).
- Coverage of records, exception classes, or Properties
  classes (covered by earlier specs).
