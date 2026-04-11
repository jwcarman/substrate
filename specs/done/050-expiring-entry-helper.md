# Share `ExpiringEntry<V>` between InMemoryAtomSpi and InMemoryMailboxSpi

## What to build

SonarCloud flags `InMemoryAtomSpi.java` and `InMemoryMailboxSpi.java`
at 11.6% and 18.1% duplicated lines respectively — not a lot per
file, but the duplication is real and centers on a specific pattern:
both SPIs store a `ConcurrentMap<String, EntryRecord>` where
`EntryRecord` includes an `Instant expiresAt` field and an
`isAlive(Instant now)` method, and both iterate the map the same way
in `sweep(int)`.

Extract a shared `ExpiringEntry<V>` record and a small sweep helper
so the two SPIs delegate TTL bookkeeping instead of reinventing it.
Journal stays out of this — `InMemoryJournalSpi`'s entries aren't
individually TTL'd (the TTL applies to the whole stream), so forcing
it into the same abstraction would be the over-reach we already
talked about earlier in spec iteration.

## What's duplicated today

### `InMemoryAtomSpi.Entry`

```java
private record Entry(byte[] value, String token, Instant expiresAt) {
  boolean isAlive(Instant now) {
    return now.isBefore(expiresAt);
  }
  // + equals/hashCode/toString for byte[] handling from spec 043
}
```

### `InMemoryMailboxSpi.Entry`

```java
private record Entry(Optional<byte[]> value, Instant expiresAt) {
  boolean isAlive(Instant now) {
    return now.isBefore(expiresAt);
  }
}
```

### `sweep(int maxToSweep)` — identical in both SPIs

```java
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

Ten verbatim lines per SPI.

## The extraction

### New shared record + helper

Create
`substrate-core/src/main/java/org/jwcarman/substrate/core/memory/ExpiringEntry.java`:

```java
package org.jwcarman.substrate.core.memory;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * A value paired with an absolute expiry instant. Used by in-memory SPI
 * implementations to attach TTL semantics to arbitrary value types without
 * reinventing the wrapper for each primitive.
 *
 * <p>Expiry is evaluated against the current wall-clock time. {@link #isExpired}
 * reads {@code Instant.now()} internally, so callers don't thread a clock
 * through. Per the in-memory SPI contract, expired entries are cleaned up
 * lazily on read and eagerly by the sweeper.
 *
 * @param value the wrapped value
 * @param expiresAt the instant at which the entry becomes expired
 * @param <V> the wrapped value type
 */
public record ExpiringEntry<V>(V value, Instant expiresAt) {

  public boolean isExpired() {
    return !Instant.now().isBefore(expiresAt);
  }

  /**
   * Sweep expired entries from the given map up to {@code maxToSweep}. Iterates
   * the map's entries once, removes those whose {@link #isExpired} returns true,
   * and stops after {@code maxToSweep} removals.
   *
   * @return the number of entries removed
   */
  public static <K, V> int sweepExpired(
      ConcurrentMap<K, ExpiringEntry<V>> store, int maxToSweep) {
    int removed = 0;
    Iterator<Map.Entry<K, ExpiringEntry<V>>> iterator = store.entrySet().iterator();
    while (iterator.hasNext() && removed < maxToSweep) {
      if (iterator.next().getValue().isExpired()) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }
}
```

Notes on the shape:

- **`isExpired()` is no-arg and reads `Instant.now()` internally.**
  This matches the earlier decision that "an expiring entry never
  needs to be asked if it's expired at a different 'now'" — the
  clock-injection path would require a much bigger refactor, which
  is out of scope here.
- **Only `isExpired()`, not both `isExpired` and `isAlive`.** Having
  two methods that are exact negations of each other was already
  rejected earlier — pick one name and stick with it. Callers that
  want the "alive" phrasing can use `!entry.isExpired()`.
- **`sweepExpired` is a static method on the record.** It's not a
  separate utility class because there's nothing else to put there
  and the record is the natural home.

### `InMemoryAtomSpi` after refactor

Replace the file-private `Entry` record with a type alias over
`ExpiringEntry<RawAtom>`. The atom's value and token already live
together in `RawAtom`, which is the project's existing public
record for "the stored atom contents." Reusing it here means
`InMemoryAtomSpi` stores `ExpiringEntry<RawAtom>` and its internal
Entry record goes away entirely.

```java
public class InMemoryAtomSpi extends AbstractAtomSpi {

  private final ConcurrentMap<String, ExpiringEntry<RawAtom>> store = new ConcurrentHashMap<>();

  public InMemoryAtomSpi() {
    super("substrate:atom:");
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    ExpiringEntry<RawAtom> entry =
        new ExpiringEntry<>(new RawAtom(value, token), Instant.now().plus(ttl));
    ExpiringEntry<RawAtom> previous =
        store.compute(key, (k, existing) -> {
          if (existing != null && !existing.isExpired()) {
            return existing;
          }
          return entry;
        });
    if (previous != entry) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    ExpiringEntry<RawAtom> entry = store.get(key);
    if (entry == null) return Optional.empty();
    if (entry.isExpired()) {
      store.remove(key, entry);
      return Optional.empty();
    }
    return Optional.of(entry.value());
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    ExpiringEntry<RawAtom> next =
        new ExpiringEntry<>(new RawAtom(value, token), Instant.now().plus(ttl));
    ExpiringEntry<RawAtom> result =
        store.compute(key, (k, existing) -> {
          if (existing == null || existing.isExpired()) return null;
          return next;
        });
    return result == next;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    ExpiringEntry<RawAtom> result =
        store.compute(key, (k, existing) -> {
          if (existing == null || existing.isExpired()) return null;
          return new ExpiringEntry<>(existing.value(), Instant.now().plus(ttl));
        });
    return result != null;
  }

  @Override
  public int sweep(int maxToSweep) {
    return ExpiringEntry.sweepExpired(store, maxToSweep);
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }
}
```

The file-private `Entry` record disappears. The byte[]-equals/
hashCode/toString overrides also disappear because `RawAtom`
already has them (from spec 043). Net effect: roughly 50 lines
removed, including the Entry record, its equality overrides, and
the sweep loop.

### `InMemoryMailboxSpi` after refactor

Mailbox's Entry holds `Optional<byte[]>` to represent "created but
not yet delivered" vs "delivered with value." Reuse
`ExpiringEntry<Optional<byte[]>>`:

```java
public class InMemoryMailboxSpi extends AbstractMailboxSpi {

  private final ConcurrentMap<String, ExpiringEntry<Optional<byte[]>>> store =
      new ConcurrentHashMap<>();

  public InMemoryMailboxSpi() {
    super("substrate:mailbox:");
  }

  @Override
  public void create(String key, Duration ttl) {
    store.put(key, new ExpiringEntry<>(Optional.empty(), Instant.now().plus(ttl)));
  }

  @Override
  public void deliver(String key, byte[] value) {
    store.compute(key, (k, existing) -> {
      if (existing == null || existing.isExpired()) {
        throw new MailboxExpiredException(key);
      }
      if (existing.value().isPresent()) {
        throw new MailboxFullException(key);
      }
      return new ExpiringEntry<>(Optional.of(value), existing.expiresAt());
    });
  }

  @Override
  public Optional<byte[]> get(String key) {
    ExpiringEntry<Optional<byte[]>> entry = store.get(key);
    if (entry == null || entry.isExpired()) {
      if (entry != null) store.remove(key, entry);
      throw new MailboxExpiredException(key);
    }
    return entry.value();
  }

  @Override
  public int sweep(int maxToSweep) {
    return ExpiringEntry.sweepExpired(store, maxToSweep);
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }
}
```

The file-private `Entry` record disappears. Roughly 15 lines
removed.

## Acceptance criteria

### New class

- [ ] `org.jwcarman.substrate.core.memory.ExpiringEntry<V>` exists
      as a `public record` with components `(V value, Instant expiresAt)`.
- [ ] `ExpiringEntry` exposes `public boolean isExpired()` that
      reads `Instant.now()` and compares against `expiresAt`.
      There is NO `isAlive()` counterpart — callers use
      `!entry.isExpired()` if they want that phrasing.
- [ ] `ExpiringEntry` exposes
      `public static <K, V> int sweepExpired(ConcurrentMap<K, ExpiringEntry<V>>, int)`
      that iterates the map, removes expired entries up to the
      batch size, and returns the removed count.
- [ ] `ExpiringEntry` has class-level Javadoc explaining its role
      and the clock-reading semantics.

### `InMemoryAtomSpi` refactor

- [ ] `InMemoryAtomSpi` no longer declares its own `Entry` record.
- [ ] The store type is
      `ConcurrentMap<String, ExpiringEntry<RawAtom>>`.
- [ ] `create`/`read`/`set`/`touch` still satisfy the full
      `AtomSpi` contract — semantics, atomicity, and exception
      types preserved exactly.
- [ ] `sweep(int)` delegates to `ExpiringEntry.sweepExpired(store, max)`.
- [ ] All existing `InMemoryAtomSpiTest` tests pass unchanged.

### `InMemoryMailboxSpi` refactor

- [ ] `InMemoryMailboxSpi` no longer declares its own `Entry` record.
- [ ] The store type is
      `ConcurrentMap<String, ExpiringEntry<Optional<byte[]>>>`.
- [ ] `create`/`deliver`/`get` still satisfy the full `MailboxSpi`
      contract, including the `MailboxExpiredException` and
      `MailboxFullException` throws paths.
- [ ] `sweep(int)` delegates to `ExpiringEntry.sweepExpired(store, max)`.
- [ ] All existing `InMemoryMailboxSpiTest` tests pass unchanged.

### Build and Sonar

- [ ] `./mvnw -pl substrate-core verify` passes locally.
- [ ] `./mvnw spotless:check` passes.
- [ ] SonarCloud duplication on the next scan drops for
      `InMemoryAtomSpi.java` (target: < 5%) and
      `InMemoryMailboxSpi.java` (target: < 8%).
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- `ExpiringEntry` lives in `org.jwcarman.substrate.core.memory`
  (the package the in-memory SPIs already share) — not in a new
  `util` or `common` package. Keep the package layout tight.
- `RawAtom` is the right fit for Atom's value type because it's
  already defined as the public "stored atom contents" record and
  already has byte[]-aware equals/hashCode/toString from spec 043.
  Do NOT introduce a new wrapper type.
- For Mailbox, `Optional<byte[]>` as the value type is a slight
  code smell (the "empty" case means "created but not delivered"
  which is really a state, not an absence). A sealed interface
  `MailboxState { Pending, Delivered(byte[]) }` would be cleaner —
  but that's a larger refactor and out of scope for this spec.
  Keep `Optional<byte[]>` for now.
- The `sweepExpired` helper is intentionally generic over both K
  and V so future in-memory stores can reuse it. Today only Atom
  and Mailbox use it.
- Do NOT add `Instant now` as a parameter to `isExpired()`. The
  earlier design discussion settled this: all in-memory SPIs read
  `Instant.now()` directly in their own methods and never need to
  evaluate the same entry against a different "now" within a
  single operation. Threading a clock through would only matter
  for deterministic testing, which is a separate refactor
  (see spec 048's "out of scope" section).

## Out of scope

- `InMemoryJournalSpi`. Journal entries don't have per-entry TTLs —
  the TTL applies to the whole stream, bookkept separately — so
  forcing Journal into `ExpiringEntry` would require reshaping
  either Journal or the abstraction. Leave Journal's TTL logic
  alone.
- Clock injection for deterministic TTL testing. That would turn
  the wall-clock TTL tests in `InMemoryAtomSpiTest` and
  `DefaultAtomTest` into instant-complete tests, but requires
  changing SPI constructor signatures to accept a `Clock` and
  plumbing it through auto-configuration. Covered by the
  "out of scope" note in spec 048; could be a future spec if the
  savings are worth the API surface cost.
- Changing the public `AtomSpi` / `MailboxSpi` interfaces.
- Moving `RawAtom` or introducing a new stored-value record for
  Mailbox.
