# Close backend SPI coverage gaps in Nats, Cassandra, RabbitMQ, and friends

## What to build

Several backend SPI implementations sit in the 82–90% coverage
range. Individually the gaps are small, but collectively they
account for the lion's share of uncovered lines left in the
project. Bringing these to parity with the higher-coverage
backends (Postgres, Redis, Mongo, Dynamo typically at 92–95%) is
the single biggest coverage improvement available.

## Sites

From the live Sonar scan, ordered by uncovered-line count:

| File | Coverage | Uncovered |
|---|---|---|
| `substrate-nats/.../journal/NatsJournalSpi.java` | 83.4% | 17 |
| `substrate-rabbitmq/.../journal/RabbitMqJournalSpi.java` | 89.7% | 14 |
| `substrate-nats/.../mailbox/NatsMailboxSpi.java` | 84.6% | 9 |
| `substrate-core/.../memory/journal/InMemoryJournalSpi.java` | 89.3% | 8 |
| `substrate-cassandra/.../journal/CassandraJournalSpi.java` | 82.0% | 8 |
| `substrate-core/.../subscription/DefaultCallbackSubscription.java` | 89.3% | 6 |
| `substrate-postgresql/.../notifier/PostgresNotifierSpi.java` | 91.7% | 6 |
| `substrate-hazelcast/.../journal/HazelcastJournalSpi.java` | 88.6% | 5 |
| `substrate-core/.../subscription/BlockingBoundedHandoff.java` | 88.6% | 4 |
| `substrate-core/.../journal/DefaultJournal.java` | 90.2% | 7 |
| `substrate-core/.../subscription/FeederSupport.java` | 91.2% | 2 |

Total: ~86 uncovered lines. Pushing all of these to 95%+ would
close most of the remaining coverage gap in the project.

## How to work this

Drive from JaCoCo reports per module, not from Sonar line numbers
(which may drift as other specs land). For each module:

1. Run `./mvnw -pl substrate-<module> verify`.
2. Open `substrate-<module>/target/site/jacoco/index.html`.
3. Drill into each listed class to see red (uncovered) lines and
   branches.
4. Add tests that exercise those lines.

## Expected gap patterns

**Journal SPIs**: the lifecycle state machine (active → completed
→ expired) often has corner cases that the happy-path tests miss.
In particular:
- `markComplete(key)` when the journal is already complete
  (idempotency)
- `markComplete(key)` followed by `append(key, …)` (should throw
  `JournalCompletedException`)
- `delete(key)` on a completed journal
- `sweep(batchSize)` on a backend that has both active and
  expired entries
- `readAfter(key, checkpoint)` with a checkpoint past the last
  entry

**Mailbox SPIs**: the expired/full error paths. Most tests cover
the happy `create → deliver → get` path but skip the "deliver to
nonexistent mailbox," "get from expired mailbox," and
"second deliver throws full" paths. Spec 041 already fixed the
"no assertion" versions of these; this spec adds test coverage
where the branches are truly unexercised.

**Handoffs**: the terminal transitions. `BlockingBoundedHandoff`'s
`markCompleted` / `markExpired` / `markDeleted` / `error` paths
and the interaction between "mark arrives during a blocked push"
and "mark arrives during a blocked pull."

**`DefaultCallbackSubscription`**: the error-in-handler branches
introduced in spec 033. The `onError`, `onExpiration`, `onDelete`,
`onComplete` handler-throws-exception branches may not all be
exercised.

**`DefaultJournal`**: preload handling edge cases (empty preload,
preload with checkpoint already past the preloaded entries).

**`FeederSupport`**: the `"__DELETED__"` notification path, the
interrupt-during-step path, and the
`catch (RuntimeException)` → `handoff.error(e)` path may not all
be exercised in `FeederSupportTest`.

## Acceptance criteria

Per file:

- [ ] Coverage rises to ≥ 95% on the next Sonar scan, OR the
      residual uncovered lines are documented as genuinely
      unreachable (e.g., defensive `if (impossible) throw` guards)
      with a one-line comment explaining why.
- [ ] New tests use the module's established test style (unit
      tests for logic, ITs via testcontainers for backend
      behavior). Prefer unit tests where possible.
- [ ] Do NOT introduce new Awaitility sleeps or `Thread.sleep`
      calls — spec 048 established the latch pattern for
      subscription tests.
- [ ] Do NOT change production code to make it easier to test.
      If a branch is hard to reach through the public API, it
      may be defensive and worth leaving alone — document in
      the test file comment.

Build:

- [ ] `./mvnw verify` passes from the root.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Recommended order

Work module-by-module rather than file-by-file to keep context
local:

1. `substrate-core` — `InMemoryJournalSpi`, `DefaultJournal`,
   `DefaultCallbackSubscription`, `BlockingBoundedHandoff`,
   `FeederSupport`. All in one module, related code paths.
2. `substrate-nats` — `NatsJournalSpi`, `NatsMailboxSpi`.
   Single module, shared backend fixture (the NATS container
   from spec 049 is already shared).
3. `substrate-cassandra` — `CassandraJournalSpi`.
4. `substrate-rabbitmq` — `RabbitMqJournalSpi`.
5. `substrate-hazelcast` — `HazelcastJournalSpi`.
6. `substrate-postgresql` — `PostgresNotifierSpi`.

Run `./mvnw -pl <module> verify` after each module's tests to
catch regressions early.

## Out of scope

- Changing the SPI interfaces. All new tests must exercise the
  existing public contracts.
- Adding new SPI features to improve testability.
- Testing private methods directly via reflection. If a branch
  is unreachable through the public API, leave it.
- Moving existing tests between files or renaming them.
- Raising coverage on files already above 95%.
