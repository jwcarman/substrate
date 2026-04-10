# Sweep SPI and core sweeper threads

**Depends on: spec 019 (intentionally-leased cleanup) must be completed
first.** Spec 019 establishes the max-TTL infrastructure and
`SubstrateProperties` layout that this spec extends with sweep
configuration.

## What to build

Add a uniform background-cleanup mechanism for expired records across
all three primitives (`Atom`, `Journal`, `Mailbox`), usable by backends
that do not have native TTL support.

The design has three parts:

1. **A `sweep(int maxToSweep)` method on each primitive's SPI**, with a
   default no-op implementation in the corresponding
   `Abstract*Spi`. Backends with native TTL (Redis, DynamoDB, Mongo,
   Hazelcast, Cassandra, NATS KV, etc.) inherit the no-op and never
   think about sweeping. Backends without native TTL (Postgres in the
   current catalog, MySQL / SQLite / plain-filesystem if added later)
   override the method with a real implementation.
2. **A sweeper thread per primitive in `substrate-core`** that
   periodically calls `spi.sweep(batchSize)` until the returned count
   is less than the batch size, then sleeps until the next interval.
3. **Filter-on-read as the correctness guarantee.** Every read in
   every backend `WHERE expires_at > now()` — or equivalent. Sweep is
   only for bounded storage; reads never return expired data
   regardless of sweeper health.

## Context: why this design

Every backend in substrate's catalog except Postgres has native TTL
support. For those, sweep is unnecessary — the backend handles it. But
the substrate-wide "intentionally leased" tenet promises *zero orphan
surface*, which means every backend must guarantee bounded storage.
Postgres needs explicit cleanup to fulfill that promise.

Rather than shipping per-backend sweeper logic in each backend module
(easy to get wrong, duplicated, hard to test), this spec moves the
**sweeper loop** into substrate-core as a cross-cutting concern, and
keeps the **sweep primitive** in the SPI as a one-method contract. The
backend says "here's how you delete a batch of expired records"; core
decides when and how often to call it.

Native-TTL backends inherit a no-op default and pay almost nothing —
~6 KB of dormant virtual thread memory and one method call per minute
that returns 0 in nanoseconds. The simplicity of "every backend has a
sweeper, most are no-ops" is worth more than the optimization of "only
create sweepers for backends that need them."

## API changes

### SPI method additions

Each primitive's SPI gains a `sweep` method. The Javadoc specifies
the contract precisely:

```java
// In AtomSpi, JournalSpi, and MailboxSpi (all three get the same shape)

/**
 * Delete up to {@code maxToSweep} expired records from the backend.
 *
 * <p>Implementations that rely on native backend TTL (Redis EXPIRE,
 * DynamoDB TTL, Mongo TTL indexes, etc.) should leave this method
 * as the default no-op inherited from {@link AbstractAtomSpi} (or
 * the matching abstract base for other primitives).
 *
 * <p>Implementations that do not have native TTL support (e.g., the
 * Postgres backend) must override this with a batched physical
 * delete of expired records.
 *
 * <p>The sweeper thread in substrate-core calls this method on a
 * fixed schedule and drains in a loop: when the returned count
 * equals {@code maxToSweep}, it immediately calls again to keep
 * draining; when the returned count is less than {@code maxToSweep},
 * it stops draining and sleeps until the next scheduled tick.
 *
 * <p>Implementations must be safe to call concurrently from multiple
 * nodes in a multi-instance deployment. For database backends, use
 * the canonical "concurrent workers" pattern for your engine — e.g.,
 * {@code DELETE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)}
 * for Postgres.
 *
 * @param maxToSweep the maximum number of records to delete in this
 *        call; must be positive
 * @return the actual number of records deleted — zero if none were
 *         found, exactly {@code maxToSweep} if more likely remain,
 *         something in between otherwise
 */
int sweep(int maxToSweep);
```

### Abstract base class defaults

Each `Abstract*Spi` provides a no-op default. Native-TTL backends
inherit this automatically.

```java
// AbstractAtomSpi — in substrate-core, org.jwcarman.substrate.core.atom
public abstract class AbstractAtomSpi implements AtomSpi {

  // ... existing prefix() / atomKey() methods ...

  @Override
  public int sweep(int maxToSweep) {
    return 0;   // native-TTL backends inherit; no work to do
  }
}
```

Same pattern in `AbstractJournalSpi` and `AbstractMailboxSpi`.

### In-memory SPI implementations

The three in-memory SPIs get **real** `sweep` implementations (not
the inherited no-op). The reason is bounded memory in long-running
dev/test processes — lazy expiry on read is fine for correctness,
but a never-read key can accumulate expired garbage forever.

```java
// InMemoryAtomSpi — in substrate-core, org.jwcarman.substrate.core.memory.atom
@Override
public int sweep(int maxToSweep) {
  Instant now = Instant.now();
  int removed = 0;
  var iterator = store.entrySet().iterator();
  while (iterator.hasNext() && removed < maxToSweep) {
    if (!iterator.next().getValue().isAlive(now)) {
      iterator.remove();
      removed++;
    }
  }
  return removed;
}
```

Note: this iterates the `ConcurrentHashMap`, which is weakly consistent —
the iterator may see a subset of entries present at any moment.
Acceptable for sweep because the next tick will catch anything missed.

Similar implementations for `InMemoryJournalSpi` and
`InMemoryMailboxSpi`, each iterating their respective stores and
removing dead entries up to the batch limit.

## Core sweeper classes

### `AtomSweeper`

```java
package org.jwcarman.substrate.core.atom;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Background sweeper that periodically calls {@link AtomSpi#sweep(int)}
 * to physically remove expired atoms from the backend.
 *
 * <p>Runs on a dedicated virtual thread. Each tick drains expired
 * records in batches until fewer than {@code batchSize} are returned,
 * capped at {@link #MAX_ITERATIONS_PER_TICK} iterations to prevent
 * runaway loops when write rate matches expiry rate.
 *
 * <p>Errors are logged at WARN and swallowed — a failing sweep does
 * not crash the application and does not block subsequent ticks.
 */
public class AtomSweeper implements AutoCloseable {

  private static final Log log = LogFactory.getLog(AtomSweeper.class);
  private static final int MAX_ITERATIONS_PER_TICK = 100;

  private final AtomSpi spi;
  private final int batchSize;
  private final ScheduledExecutorService scheduler;

  public AtomSweeper(AtomSpi spi, Duration interval, int batchSize) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
    }
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive: " + interval);
    }
    this.spi = spi;
    this.batchSize = batchSize;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("substrate-atom-sweeper", 0).factory());

    long intervalMs = interval.toMillis();
    long jitterMs = ThreadLocalRandom.current().nextLong(intervalMs / 2 + 1);
    scheduler.scheduleWithFixedDelay(
        this::tick,
        intervalMs + jitterMs,
        intervalMs,
        TimeUnit.MILLISECONDS);
  }

  void tick() {
    try {
      int totalDeleted = 0;
      for (int i = 0; i < MAX_ITERATIONS_PER_TICK; i++) {
        int deleted = spi.sweep(batchSize);
        totalDeleted += deleted;
        if (deleted < batchSize) {
          break;
        }
      }
      if (totalDeleted > 0 && log.isDebugEnabled()) {
        log.debug("Swept " + totalDeleted + " expired atoms");
      }
    } catch (RuntimeException e) {
      log.warn("Atom sweep failed; will retry on next tick", e);
    }
  }

  @Override
  public void close() {
    scheduler.shutdown();
  }
}
```

### `JournalSweeper` and `MailboxSweeper`

Identical structure, different SPI and log messages. Each in its
respective primitive package:

- `org.jwcarman.substrate.core.journal.JournalSweeper` — depends on
  `JournalSpi`, virtual thread name `substrate-journal-sweeper`, logs
  "Swept N expired journal entries".
- `org.jwcarman.substrate.core.mailbox.MailboxSweeper` — depends on
  `MailboxSpi`, virtual thread name `substrate-mailbox-sweeper`, logs
  "Swept N expired mailboxes".

The three classes share enough structure that a generic base
(`AbstractSweeper<T>` with a `Function<Integer, Integer> sweepOp`)
could factor the duplication, but spec 022 keeps them as three
concrete copies. Reasons: (1) each class is small — ~50 lines; (2) a
generic base obscures log messages and thread names; (3) factoring
can happen later as a follow-on refactor if the duplication hurts.

## Configuration

Extend `SubstrateProperties` (from spec 019) with a `sweep` nested
record per primitive.

```java
@ConfigurationProperties(prefix = "substrate")
public record SubstrateProperties(
    AtomProperties atom,
    JournalProperties journal,
    MailboxProperties mailbox) {

  public record AtomProperties(Duration maxTtl, SweepProperties sweep) {
    public AtomProperties {
      if (maxTtl == null) maxTtl = Duration.ofHours(24);
      if (sweep == null) sweep = SweepProperties.defaults();
    }
  }

  public record JournalProperties(Duration maxTtl, SweepProperties sweep) {
    public JournalProperties {
      if (maxTtl == null) maxTtl = Duration.ofDays(7);
      if (sweep == null) sweep = SweepProperties.defaults();
    }
  }

  public record MailboxProperties(Duration maxTtl, SweepProperties sweep) {
    public MailboxProperties {
      if (maxTtl == null) maxTtl = Duration.ofMinutes(30);
      if (sweep == null) sweep = SweepProperties.defaults();
    }
  }

  public record SweepProperties(boolean enabled, Duration interval, int batchSize) {
    public static SweepProperties defaults() {
      return new SweepProperties(true, Duration.ofMinutes(1), 1000);
    }
  }
}
```

Example configuration:

```yaml
substrate:
  atom:
    max-ttl: 24h
    sweep:
      enabled: true
      interval: 1m
      batch-size: 1000
  journal:
    max-ttl: 7d
    sweep:
      enabled: true
      interval: 5m        # longer interval — journal entries are typically longer-lived
      batch-size: 1000
  mailbox:
    max-ttl: 30m
    sweep:
      enabled: true
      interval: 1m
      batch-size: 1000
```

Defaults (applied when properties are absent):

| Primitive | `max-ttl` | `sweep.enabled` | `sweep.interval` | `sweep.batch-size` |
|---|---|---|---|---|
| atom | 24h | true | 1m | 1000 |
| journal | 7d | true | 1m | 1000 |
| mailbox | 30m | true | 1m | 1000 |

The journal default interval in the example above is longer, but the
baked-in default is 1m for consistency — operators can tune per-deployment.

## Auto-configuration

Register the three sweeper beans in `SubstrateAutoConfiguration`
(at its new location per spec 019,
`org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration`).

```java
@Bean(destroyMethod = "close")
@ConditionalOnBean(AtomSpi.class)
@ConditionalOnProperty(
    prefix = "substrate.atom.sweep",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public AtomSweeper atomSweeper(AtomSpi atomSpi, SubstrateProperties props) {
  var sweep = props.atom().sweep();
  return new AtomSweeper(atomSpi, sweep.interval(), sweep.batchSize());
}

@Bean(destroyMethod = "close")
@ConditionalOnBean(JournalSpi.class)
@ConditionalOnProperty(
    prefix = "substrate.journal.sweep",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public JournalSweeper journalSweeper(JournalSpi journalSpi, SubstrateProperties props) {
  var sweep = props.journal().sweep();
  return new JournalSweeper(journalSpi, sweep.interval(), sweep.batchSize());
}

@Bean(destroyMethod = "close")
@ConditionalOnBean(MailboxSpi.class)
@ConditionalOnProperty(
    prefix = "substrate.mailbox.sweep",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public MailboxSweeper mailboxSweeper(MailboxSpi mailboxSpi, SubstrateProperties props) {
  var sweep = props.mailbox().sweep();
  return new MailboxSweeper(mailboxSpi, sweep.interval(), sweep.batchSize());
}
```

Notes:

- `destroyMethod = "close"` ensures the scheduler is shut down
  cleanly when the Spring context closes.
- `@ConditionalOnBean(AtomSpi.class)` means the sweeper only exists
  if an actual SPI is registered. No SPI, no sweeper — nothing to
  sweep.
- `@ConditionalOnProperty(matchIfMissing = true)` means sweepers are
  on by default. Operators disable per-primitive via
  `substrate.atom.sweep.enabled=false` etc.

## Filter-on-read as the correctness guarantee

**This spec does not introduce filter-on-read — that's already a
per-SPI responsibility.** Every backend, sweeper or not, must ensure
that `read` / `get` / `readAfter` / etc. never return records whose
`expires_at` is in the past. In-memory SPIs enforce this via lazy
cleanup on read (spec 018 and spec 019); real backends enforce it via
their native TTL mechanism.

The sweeper's only job is **bounded storage**. Sweep and
filter-on-read are complementary:

- **Filter-on-read without sweep:** correct reads, unbounded storage
  growth. Unacceptable long-term — violates the leased tenet.
- **Sweep without filter-on-read:** bounded storage, but a race
  between sweep and read can briefly return expired data. Unacceptable.
- **Both:** correct reads at all times, bounded storage. Required.

Every backend must have both. Spec 022 provides the sweep
infrastructure; spec 019 and earlier specs ensured filter-on-read.

## Multi-node safety

The sweeper runs on every application instance. In a multi-node
deployment, that means N sweepers can hit the same shared backend
storage concurrently.

**For native-TTL backends** (Redis, DynamoDB, Mongo, etc.), sweeper
calls return 0 — no backend I/O, no contention. N-way concurrent
no-ops are harmless.

**For backends that actually implement sweep** (Postgres, future
TTL-less backends), the SPI implementation **must** use a
concurrent-safe pattern. For Postgres, that's `SELECT ... FOR UPDATE
SKIP LOCKED`:

```sql
DELETE FROM substrate_atoms
 WHERE key IN (
   SELECT key FROM substrate_atoms
    WHERE expires_at < now()
    ORDER BY expires_at
    LIMIT ?
    FOR UPDATE SKIP LOCKED
 );
```

This is the canonical Postgres pattern for concurrent workers
consuming from the same table. N sweepers each grab disjoint batches
without blocking. Spec 020 or a follow-on backend-specific spec will
implement this inside `PostgresAtomSpi` / `PostgresJournalSpi` /
`PostgresMailboxSpi`.

**Leader election is explicitly not used.** Sweep operations are
idempotent and commutative — running them on N nodes is no less
correct than running them on one, and SKIP LOCKED turns the apparent
redundancy into useful parallelism. Adding ShedLock-style leader
election would reduce throughput on large deletion backlogs and add
failure modes (what if the leader dies?) for zero correctness
benefit.

The jittered initial delay in each `AtomSweeper` / `JournalSweeper` /
`MailboxSweeper` spreads the wall-clock tick instants across nodes so
they don't all fire at exactly the same moment, further reducing
transient contention spikes.

## Scope boundaries

### Not in this spec

- **Postgres implementation of sweep.** That lives in the Postgres
  backend module (spec 020 or a follow-on per-backend spec). Spec
  022 only defines the SPI contract and the core sweeper
  infrastructure; it does not implement `sweep` for any specific
  backend other than the in-memory fallbacks.
- **Alternative coordination mechanisms** (ShedLock, leader
  election, distributed locks). See the "Multi-node safety" section
  for rationale.
- **Metrics / observability.** A Micrometer-based metric for "atoms
  swept per tick" would be valuable but is out of scope. Add as a
  follow-on spec if it proves needed.

### In this spec

- The `sweep(int)` method on all three SPIs, with no-op default in
  the `Abstract*Spi` bases
- Real `sweep` implementations for the three in-memory SPIs
- `AtomSweeper` / `JournalSweeper` / `MailboxSweeper` classes in
  `substrate-core`
- `SubstrateProperties` extension with `sweep` subproperties per
  primitive
- Auto-configuration bean wiring with `@ConditionalOnBean` and
  `@ConditionalOnProperty(matchIfMissing=true)`
- Unit tests for each sweeper and each in-memory SPI's `sweep`
  implementation

## Acceptance criteria

### SPI changes

- [ ] `AtomSpi` has a new method `int sweep(int maxToSweep)`.
- [ ] `JournalSpi` has a new method `int sweep(int maxToSweep)`.
- [ ] `MailboxSpi` has a new method `int sweep(int maxToSweep)`.
- [ ] `AbstractAtomSpi.sweep(int)` provides a no-op default
      returning 0.
- [ ] `AbstractJournalSpi.sweep(int)` provides a no-op default
      returning 0.
- [ ] `AbstractMailboxSpi.sweep(int)` provides a no-op default
      returning 0.
- [ ] Javadoc on each SPI method documents the drain-loop contract,
      the concurrent-safety requirement, and the expectation that
      native-TTL backends inherit the default.

### In-memory SPI implementations

- [ ] `InMemoryAtomSpi.sweep(int maxToSweep)` removes at most
      `maxToSweep` expired entries and returns the count. Verified
      by a unit test that writes 5 atoms with 50 ms TTL, sleeps past
      expiry, calls `sweep(10)`, and asserts the return value is 5
      and the store is empty.
- [ ] `InMemoryJournalSpi.sweep(int maxToSweep)` does the same for
      journal entries.
- [ ] `InMemoryMailboxSpi.sweep(int maxToSweep)` does the same for
      mailboxes.
- [ ] `InMemoryAtomSpi.sweep` respects `maxToSweep` — calling
      `sweep(3)` on a store with 10 expired entries returns 3 and
      leaves 7 expired entries present. Verified by unit test.
- [ ] Calling `sweep(1000)` on a store with no expired entries
      returns 0. Verified by unit test.

### Core sweeper classes

- [ ] `org.jwcarman.substrate.core.atom.AtomSweeper` exists and
      implements `AutoCloseable`.
- [ ] `org.jwcarman.substrate.core.journal.JournalSweeper` exists
      and implements `AutoCloseable`.
- [ ] `org.jwcarman.substrate.core.mailbox.MailboxSweeper` exists
      and implements `AutoCloseable`.
- [ ] Each sweeper's constructor validates that `batchSize` is
      positive and `interval` is positive non-zero, throwing
      `IllegalArgumentException` otherwise.
- [ ] Each sweeper uses a virtual thread backed
      `ScheduledExecutorService` with a named factory (e.g.,
      `substrate-atom-sweeper`).
- [ ] Each sweeper's initial delay has jitter up to 50% of the
      configured interval. Verified by a test that inspects the
      `ScheduledFuture.getDelay()` of the first scheduled task.
- [ ] Each sweeper's `tick()` method drains in a loop: call
      `spi.sweep(batchSize)`, accumulate count, stop when returned
      count is less than batch size or iteration cap is reached.
      Verified by a test with a mock SPI that returns
      `batchSize, batchSize, 0, 0, 0` on successive calls and
      asserting the loop calls it exactly 3 times.
- [ ] Each sweeper's `tick()` method has an iteration cap
      (MAX_ITERATIONS_PER_TICK = 100) to prevent runaway loops when
      the SPI keeps returning `batchSize`. Verified by a test with a
      mock SPI that always returns `batchSize`; the loop stops
      after exactly 100 iterations.
- [ ] Each sweeper's `tick()` swallows `RuntimeException` thrown
      from `spi.sweep` and logs at WARN level. Subsequent ticks
      continue to run after an error. Verified by a test with a
      mock SPI that throws, followed by a mock that returns 0.
- [ ] Each sweeper's `close()` shuts down its scheduler cleanly.
      Verified by a test that creates a sweeper, closes it, and
      asserts the scheduler is terminated.

### Configuration

- [ ] `SubstrateProperties.AtomProperties` has a `sweep` field of
      type `SweepProperties` with `enabled`, `interval`, `batchSize`.
- [ ] Same for `JournalProperties` and `MailboxProperties`.
- [ ] Defaults applied when the `sweep` sub-record is absent:
      `enabled=true`, `interval=1m`, `batchSize=1000`.
- [ ] Overriding `substrate.atom.sweep.interval: 5m` in
      `application.yml` is observed by `AtomSweeper` at construction.
      Verified by an autoconfiguration test.

### Auto-configuration

- [ ] `SubstrateAutoConfiguration` registers `AtomSweeper`,
      `JournalSweeper`, `MailboxSweeper` beans each with
      `destroyMethod = "close"`.
- [ ] Each sweeper bean is gated by `@ConditionalOnBean(SpiClass)`
      so it only exists when an SPI is registered.
- [ ] Each sweeper bean is gated by `@ConditionalOnProperty`
      with `matchIfMissing = true` so sweepers are on by default.
- [ ] Setting `substrate.atom.sweep.enabled: false` prevents the
      `AtomSweeper` bean from being created. Verified by
      `@SpringBootTest` context test.

### Tests

- [ ] `substrate-core/src/test/java/org/jwcarman/substrate/core/atom/AtomSweeperTest.java`
      covers constructor validation, tick drain-loop, iteration cap,
      error swallowing, and close().
- [ ] Same for `JournalSweeperTest` and `MailboxSweeperTest`.
- [ ] `InMemoryAtomSpiTest.sweepTest()` covers the real sweep
      implementation (expired entries removed, max respected,
      no-op when nothing expired).
- [ ] Same for `InMemoryJournalSpiTest` and `InMemoryMailboxSpiTest`.
- [ ] Tests use Awaitility for any time-sensitive assertions (no
      `Thread.sleep` in tests, per existing project convention).
- [ ] All existing tests continue to pass.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers on every new file.
- [ ] No `@SuppressWarnings` annotations introduced.

## Implementation notes

- The three sweeper classes (`AtomSweeper`, `JournalSweeper`,
  `MailboxSweeper`) are structurally nearly identical. A generic
  base class or abstraction could factor the duplication, but this
  spec keeps them separate for clarity. Refactoring to a shared
  `AbstractSweeper<Spi>` is a reasonable follow-on if the
  duplication proves painful, but not required.
- Jitter on the *initial delay only*, not on the per-tick interval.
  Per-tick jitter isn't needed — once the initial delays have
  phase-shifted the N nodes, fixed intervals keep them out of phase.
- `ScheduledExecutorService.scheduleWithFixedDelay` (not
  `scheduleAtFixedRate`) because we want *N seconds between ticks*,
  not *N seconds between start times*. A slow sweep on a large
  backlog should not cause the next tick to fire immediately.
- `MAX_ITERATIONS_PER_TICK = 100` with `batchSize = 1000` means a
  single tick can delete up to 100 × 1000 = 100,000 records. That's
  a reasonable cap — enough to handle normal backlogs without
  starving other work, small enough to guarantee the tick completes
  in bounded time.
- Virtual threads via `Thread.ofVirtual().name("...", 0).factory()`.
  The `0` is the starting sequence number for the name suffix; the
  thread factory auto-increments it. Since there's only one worker
  per sweeper, the name stays as `substrate-atom-sweeper-0`.
- The `@ConditionalOnBean` guards are load-bearing. Without them,
  the sweeper would try to construct with a null SPI bean if the
  application context doesn't have one registered. With them, no
  SPI means no sweeper — clean and non-error-producing.
- Document in the Postgres backend spec (when it exists) that the
  `PostgresAtomSpi.sweep` implementation must use the
  `DELETE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)`
  pattern. Spec 022 only enforces "implement sweep safely in a
  concurrent environment"; the actual SQL is the backend's
  responsibility.
- If the sweeper ever gets a genuine observability story (metrics,
  last-successful-sweep timestamps, etc.), add it as a separate
  follow-on spec rather than bolting it onto this one.
