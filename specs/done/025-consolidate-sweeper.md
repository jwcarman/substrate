# Consolidate sweeper classes into a single `Sweeper`

**Depends on: spec 022 (sweep SPI + core sweepers) must be completed
first.** Spec 022 introduced `AtomSweeper`, `JournalSweeper`, and
`MailboxSweeper` — three structurally identical classes that differ
only in which SPI they call, what their thread is named, and what
prose appears in their log messages. This spec consolidates them into
a single `Sweeper` class plus a `Sweepable` marker interface.

## What to build

Replace the three per-primitive sweeper classes with one generic
`Sweeper` class that takes a `Class<?> primitiveType` for
naming/logging and a `Sweepable` reference for the actual sweep call.
Introduce a `Sweepable` interface that the three SPI interfaces extend,
so the `sweep(int)` method is declared once and inherited by each SPI.

The behavior is unchanged from spec 022 — same drain loop, same
jittered scheduling, same WARN-on-error-and-continue, same
`AutoCloseable` lifecycle. This is purely a refactor to eliminate
boilerplate.

## Rationale

Spec 022 shipped three classes, ~50 lines each, that are structurally
identical:

```java
// AtomSweeper.java, JournalSweeper.java, MailboxSweeper.java
// All three have the same:
//   - constructor validating batchSize and interval
//   - ScheduledExecutorService backed by a virtual thread factory
//   - jittered initial delay
//   - scheduleWithFixedDelay setup
//   - tick() with drain loop, iteration cap, WARN-on-error
//   - close() that shuts down the scheduler
//
// They differ only in:
//   - the SPI type they hold (AtomSpi, JournalSpi, MailboxSpi)
//   - the virtual thread name ("substrate-atom-sweeper", etc.)
//   - the log message ("Swept N expired atoms", etc.)
```

That's ~150 lines of production code + ~300 lines of near-identical
test code for three copies of the same thing. Consolidation into one
`Sweeper` class plus one `Sweepable` interface cuts the line count
roughly in half, collapses three test classes into one, and
establishes a reusable pattern for any future cross-primitive
maintenance operations (e.g., if substrate later adds `Compactable`,
`Vacuumable`, etc.).

## The `Sweepable` interface

Lives in a new package `org.jwcarman.substrate.core.sweep` under
`substrate-core`. This package is the home for cross-primitive
scheduling/maintenance machinery that doesn't belong to any specific
primitive.

```java
package org.jwcarman.substrate.core.sweep;

/**
 * A backend capability: can physically remove expired records on
 * demand. Implemented by SPI interfaces for primitives that carry
 * expirable state ({@link org.jwcarman.substrate.core.atom.AtomSpi},
 * {@link org.jwcarman.substrate.core.journal.JournalSpi},
 * {@link org.jwcarman.substrate.core.mailbox.MailboxSpi}).
 *
 * <p>The {@link org.jwcarman.substrate.core.sweep.Sweeper} driver
 * class calls {@link #sweep(int)} on a scheduled cadence and uses
 * the returned count to decide whether to keep draining in this tick
 * or wait until the next scheduled call.
 *
 * <p>Backends with native TTL (Redis EXPIRE, DynamoDB TTL, Mongo TTL
 * indexes, etc.) inherit a no-op default from their corresponding
 * {@code AbstractXSpi} base class and need not override this method.
 * Backends without native TTL (e.g., PostgreSQL) must override with
 * a batched conditional delete — see the SPI javadoc on the specific
 * primitive's interface for the full contract.
 *
 * @see org.jwcarman.substrate.core.sweep.Sweeper
 */
public interface Sweepable {

  /**
   * Delete up to {@code maxToSweep} expired records from the backend.
   *
   * <p>Return exactly 0 if nothing was found, exactly {@code maxToSweep}
   * if the caller should drain again immediately, or something in
   * between if there is no more work for this tick.
   *
   * <p>Must be safe to call concurrently from multiple processes in
   * a clustered deployment. For database backends, use the canonical
   * "concurrent workers" pattern for your engine (e.g., {@code SELECT
   * ... FOR UPDATE SKIP LOCKED} on Postgres).
   *
   * @param maxToSweep maximum number of records to delete in this call;
   *                   must be positive
   * @return the actual number of records deleted
   */
  int sweep(int maxToSweep);
}
```

## SPI interface changes

Each of the three primitive SPIs currently declares `int sweep(int
maxToSweep)` directly (from spec 022). This spec moves the declaration
to `Sweepable` and has each SPI extend `Sweepable` instead:

```java
// substrate-core/.../core/atom/AtomSpi.java
package org.jwcarman.substrate.core.atom;

import org.jwcarman.substrate.core.sweep.Sweepable;

public interface AtomSpi extends Sweepable {

  // ... existing methods: create, read, set, touch, delete, atomKey ...
  // sweep(int) is now inherited from Sweepable — NO declaration here.
}
```

Same change for `JournalSpi` and `MailboxSpi`. The method declaration
is removed from each SPI; it comes from `Sweepable` instead.

**Javadoc:** the method-level javadoc that currently lives on
`AtomSpi.sweep(int)` (describing the drain-loop contract, the
concurrent-safety requirement, and the no-op-default-for-native-TTL
guidance) moves to `Sweepable.sweep(int)`. If there are any
primitive-specific notes (e.g., "Postgres journal sweepers should
also clean up expired completion markers"), those stay on the
specific SPI interface's class-level javadoc or on the backend
implementation class.

## The `Sweeper` class

```java
package org.jwcarman.substrate.core.sweep;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Background sweeper that periodically calls {@link Sweepable#sweep(int)}
 * to physically remove expired records from a primitive's backend.
 *
 * <p>Runs on a dedicated virtual thread. Each tick drains expired
 * records in batches until fewer than {@code batchSize} are returned,
 * capped at {@link #MAX_ITERATIONS_PER_TICK} iterations to prevent
 * runaway loops when write rate matches expiry rate.
 *
 * <p>Errors thrown from {@code sweep} are logged at WARN and swallowed —
 * a failing sweep does not crash the application and does not block
 * subsequent ticks.
 *
 * <p>The same {@code Sweeper} class drives all three primitives
 * (Atom, Journal, Mailbox). The {@code primitiveType} constructor
 * argument is used purely for naming the virtual thread
 * ({@code substrate-<name>-sweeper}) and annotating log messages.
 */
public class Sweeper implements AutoCloseable {

  private static final Log log = LogFactory.getLog(Sweeper.class);
  private static final int MAX_ITERATIONS_PER_TICK = 100;

  private final Class<?> primitiveType;
  private final Sweepable target;
  private final int batchSize;
  private final ScheduledExecutorService scheduler;

  public Sweeper(
      Class<?> primitiveType,
      Sweepable target,
      Duration interval,
      int batchSize) {
    if (primitiveType == null) {
      throw new IllegalArgumentException("primitiveType must not be null");
    }
    if (target == null) {
      throw new IllegalArgumentException("target must not be null");
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
    }
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive: " + interval);
    }
    this.primitiveType = primitiveType;
    this.target = target;
    this.batchSize = batchSize;

    String threadName =
        "substrate-" + primitiveType.getSimpleName().toLowerCase() + "-sweeper";
    this.scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name(threadName, 0).factory());

    long intervalMs = interval.toMillis();
    long jitterMs = (long) (Math.random() * (intervalMs / 2 + 1));
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
        int deleted = target.sweep(batchSize);
        totalDeleted += deleted;
        if (deleted < batchSize) {
          break;
        }
      }
      if (totalDeleted > 0 && log.isDebugEnabled()) {
        log.debug(
            "Swept " + totalDeleted + " expired "
                + primitiveType.getSimpleName() + " records");
      }
    } catch (RuntimeException e) {
      log.warn(
          "Sweep failed for " + primitiveType.getSimpleName()
              + "; will retry on next tick",
          e);
    }
  }

  @Override
  public void close() {
    scheduler.shutdown();
  }
}
```

## Files deleted

The three separate sweeper classes from spec 022 are removed:

- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/AtomSweeper.java`
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/journal/JournalSweeper.java`
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/mailbox/MailboxSweeper.java`

Their tests are also removed:

- [ ] `substrate-core/src/test/java/org/jwcarman/substrate/core/atom/AtomSweeperTest.java`
- [ ] `substrate-core/src/test/java/org/jwcarman/substrate/core/journal/JournalSweeperTest.java`
- [ ] `substrate-core/src/test/java/org/jwcarman/substrate/core/mailbox/MailboxSweeperTest.java`

Replaced by a single consolidated test in the new location.

## Files created

- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/sweep/Sweepable.java`
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/sweep/Sweeper.java`
- [ ] `substrate-core/src/test/java/org/jwcarman/substrate/core/sweep/SweeperTest.java`

## Auto-configuration changes

`SubstrateAutoConfiguration` currently creates three beans of three
different types (`AtomSweeper`, `JournalSweeper`, `MailboxSweeper`).
After this spec, all three beans are of the same type (`Sweeper`),
distinguished by Spring bean name:

```java
@Bean(destroyMethod = "close")
@ConditionalOnBean(AtomSpi.class)
@ConditionalOnProperty(prefix = "substrate.atom.sweep", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public Sweeper atomSweeper(AtomSpi spi, SubstrateProperties props) {
  var sweep = props.atom().sweep();
  return new Sweeper(Atom.class, spi, sweep.interval(), sweep.batchSize());
}

@Bean(destroyMethod = "close")
@ConditionalOnBean(JournalSpi.class)
@ConditionalOnProperty(prefix = "substrate.journal.sweep", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public Sweeper journalSweeper(JournalSpi spi, SubstrateProperties props) {
  var sweep = props.journal().sweep();
  return new Sweeper(Journal.class, spi, sweep.interval(), sweep.batchSize());
}

@Bean(destroyMethod = "close")
@ConditionalOnBean(MailboxSpi.class)
@ConditionalOnProperty(prefix = "substrate.mailbox.sweep", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public Sweeper mailboxSweeper(MailboxSpi spi, SubstrateProperties props) {
  var sweep = props.mailbox().sweep();
  return new Sweeper(Mailbox.class, spi, sweep.interval(), sweep.batchSize());
}
```

Notes:

- `Atom.class`, `Journal.class`, `Mailbox.class` come from
  `substrate-api`. Imports added as needed.
- The SPI parameters (`AtomSpi`, `JournalSpi`, `MailboxSpi`) go
  directly into the `Sweeper` constructor because they now implement
  `Sweepable`. No method reference, no lambda.
- Three beans of the same concrete type (`Sweeper`) differentiated by
  Spring bean name. Injecting a `Sweeper` without a `@Qualifier`
  would be ambiguous — but nothing in the substrate codebase or in
  consumer applications *should* inject a sweeper. They are
  lifecycle-managed singletons, not collaborators.

## Acceptance criteria

### Files

- [ ] `org.jwcarman.substrate.core.sweep.Sweepable` exists and has a
      single method `int sweep(int maxToSweep)` with javadoc copied
      from the existing SPI method.
- [ ] `org.jwcarman.substrate.core.sweep.Sweeper` exists with the
      shape shown above.
- [ ] `org.jwcarman.substrate.core.atom.AtomSweeper` no longer
      exists.
- [ ] `org.jwcarman.substrate.core.journal.JournalSweeper` no longer
      exists.
- [ ] `org.jwcarman.substrate.core.mailbox.MailboxSweeper` no longer
      exists.
- [ ] The corresponding `*SweeperTest` files no longer exist.
- [ ] A new `org.jwcarman.substrate.core.sweep.SweeperTest` exists
      and covers all the behavior previously tested across the three
      per-primitive tests (constructor validation, drain loop,
      iteration cap, error swallowing, close, jittered initial
      delay).

### SPI changes

- [ ] `AtomSpi extends Sweepable`.
- [ ] `JournalSpi extends Sweepable`.
- [ ] `MailboxSpi extends Sweepable`.
- [ ] Each SPI no longer declares `int sweep(int maxToSweep)`
      directly — the declaration is inherited from `Sweepable`.
- [ ] `AbstractAtomSpi.sweep(int)`, `AbstractJournalSpi.sweep(int)`,
      and `AbstractMailboxSpi.sweep(int)` still provide the no-op
      default (`return 0`). The default now satisfies the
      inherited `Sweepable.sweep` contract.
- [ ] Existing `InMemoryAtomSpi.sweep`, `InMemoryJournalSpi.sweep`,
      and `InMemoryMailboxSpi.sweep` implementations still work
      unchanged — they are now overrides of `Sweepable.sweep` via
      the SPI hierarchy.
- [ ] No backend module's SPI implementation needs to change as a
      result of this refactor (the method signature is identical;
      only its declaration site moved).

### Auto-configuration

- [ ] `SubstrateAutoConfiguration` creates three `Sweeper` beans
      (atomSweeper, journalSweeper, mailboxSweeper) instead of three
      different classes.
- [ ] Each bean is constructed as
      `new Sweeper(<PrimitiveClass>.class, spi, interval, batchSize)`.
- [ ] Each bean retains its `@ConditionalOnBean(...Spi.class)` +
      `@ConditionalOnProperty(..., matchIfMissing = true)` +
      `destroyMethod = "close"` configuration.
- [ ] Disabling via `substrate.atom.sweep.enabled=false` still
      suppresses the atom sweeper bean. Verified by the existing
      `@SpringBootTest` from spec 022 (it still passes after the
      refactor).

### Behavior

- [ ] The virtual thread name derived from `Atom.class` is
      `substrate-atom-sweeper`. Verified by a unit test that
      constructs a `Sweeper(Atom.class, ...)` and inspects the
      scheduler's thread name via a mock `Sweepable` that captures
      `Thread.currentThread().getName()` on invocation.
- [ ] The same test for `Journal.class` produces
      `substrate-journal-sweeper`, and for `Mailbox.class` produces
      `substrate-mailbox-sweeper`.
- [ ] Drain loop behavior is unchanged from spec 022 — mock
      `Sweepable` returning `batchSize, batchSize, 0` causes the
      loop to invoke it exactly 3 times.
- [ ] Iteration cap behavior is unchanged — mock `Sweepable` that
      always returns `batchSize` causes the loop to stop after
      exactly `MAX_ITERATIONS_PER_TICK` (100) calls per tick.
- [ ] Error swallowing is unchanged — mock `Sweepable` that throws
      `RuntimeException` on tick N logs at WARN and the next tick
      still fires.
- [ ] `close()` cleanly shuts down the scheduler.

### Tests

- [ ] `SweeperTest` covers:
      - constructor argument validation (null type, null target,
        zero/negative batchSize, zero/negative interval)
      - drain loop with partial batch (stops when returned < batchSize)
      - iteration cap at 100 iterations per tick
      - WARN-on-exception and continue to next tick
      - virtual thread name derivation from
        `Class<?>.getSimpleName().toLowerCase()`
      - `close()` shuts down the scheduler
      - jittered initial delay (verified by inspecting
        `ScheduledFuture.getDelay()`)
- [ ] All tests use Awaitility for any time-sensitive assertions;
      no `Thread.sleep`.
- [ ] The total line count of `SweeperTest` is less than the sum of
      the three deleted `*SweeperTest` files it replaces
      (consolidation should reduce test duplication, not relocate
      it).

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers on every new file.
- [ ] No `@SuppressWarnings` annotations introduced.
- [ ] Net line count in `substrate-core` drops by at least 100 lines
      (production + test) compared to pre-refactor state. This is an
      observation, not a hard metric — approximate.

## Implementation notes

- This is a pure refactor. Zero behavior changes compared to
  spec 022. Every test written against the old per-primitive
  sweeper classes should have an equivalent test against
  `Sweeper` — if an old test case covered something specific,
  the new test covers the same thing.
- The `Sweepable` interface lives in `substrate-core`, not
  `substrate-api`. It's a backend-facing contract (SPIs extend it,
  users never see it), consistent with where the SPI interfaces
  themselves live.
- Java's `Class<?>.getSimpleName()` returns `"Atom"` for
  `Atom.class`, which the `Sweeper` constructor lowercases to
  `"atom"` for thread naming and keeps capitalized for log prose.
  This is intentional — thread names are conventionally lowercase
  in Java, but log messages read better with the primitive type
  capitalized: "Swept 47 expired Atom records".
- `Atom.class`, `Journal.class`, `Mailbox.class` are the
  user-facing interfaces from `substrate-api`, not the SPI types
  from `substrate-core`. Using the user-facing type makes the
  thread name and log messages match the vocabulary users already
  know — "atom", "journal", "mailbox" — rather than SPI-layer
  jargon like "AtomSpi". It also avoids the awkward need to strip
  an "Spi" suffix.
- If this refactor proves useful and substrate later adds other
  cross-primitive maintenance operations (compaction, statistics,
  health checks), follow the same pattern: an interface
  (`Compactable`, `Statsable`, `HealthCheckable`) extended by the
  relevant SPIs, plus a generic driver class that consumes the
  interface. One place to implement, one place to test.
- The three `Sweeper` beans being the same type means Spring's
  `@Autowired Sweeper sweeper` would be ambiguous and fail
  context loading. This is fine — no consumer code should ever
  inject a sweeper. They are lifecycle-managed singletons, started
  on context refresh and stopped on context close. If an edge case
  ever needs direct access (e.g., "trigger a manual sweep from an
  admin endpoint"), the caller uses
  `@Autowired @Qualifier("atomSweeper") Sweeper` to disambiguate.
- The `destroyMethod = "close"` annotation parameter on each bean
  definition ensures Spring calls `Sweeper.close()` during context
  shutdown, gracefully terminating the scheduler. Do not rely on
  `@PreDestroy` — the `destroyMethod` mechanism is more explicit
  and doesn't require `jakarta.annotation-api` in `substrate-core`.
