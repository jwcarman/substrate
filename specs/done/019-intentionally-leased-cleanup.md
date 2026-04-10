# Intentionally-leased API cleanup and package migration

**Depends on: spec 018 (Atom primitive) must be completed first.** Spec 018
creates the `substrate-api` Maven module and establishes the api/core
package split. This spec migrates the existing `Journal`, `Mailbox`, and
`Notifier` types into that split and applies the "intentionally leased"
tenet to them.

## What to build

Three related changes in a single sweep through the codebase:

1. **Package + module migration** — move existing primitive types into
   the `substrate-api` + `substrate-core` layout established by spec 018.
   User-facing interfaces and value types live in `substrate-api`;
   implementations, SPIs, and in-memory fallbacks live in `substrate-core`
   under `org.jwcarman.substrate.core.*`.
2. **"Intentionally leased" API cleanup** — remove `Journal.append(T)`
   (the no-TTL overload), add creation-time `Duration ttl` to
   `MailboxFactory.create`, introduce `MailboxExpiredException`, and
   add configurable max-TTL-per-primitive enforcement in the core
   layer.
3. **Factory/Notifier renames** — convert `JournalFactory` and
   `MailboxFactory` from concrete classes to interfaces (with
   `DefaultJournalFactory` / `DefaultMailboxFactory` implementations)
   matching the `AtomFactory` pattern introduced in spec 018. Rename
   `Notifier` to `NotifierSpi` to match the other SPI naming convention.

This is a breaking change sweep. Project is at `0.1.0-SNAPSHOT` and has
not yet published; now is the cheapest moment.

## Package migration map

### `substrate-api` — user-facing interfaces and value types

All of these move **from** `substrate-core` **into** the new
`substrate-api` module. Each file gets a new package declaration and
license header stays intact.

| From (substrate-core)                                      | To (substrate-api)                                      |
|---|---|
| `org.jwcarman.substrate.core.Journal`                      | `org.jwcarman.substrate.journal.Journal`                |
| `org.jwcarman.substrate.core.JournalFactory` *(becomes interface)* | `org.jwcarman.substrate.journal.JournalFactory`         |
| `org.jwcarman.substrate.core.JournalCursor`                | `org.jwcarman.substrate.journal.JournalCursor`          |
| `org.jwcarman.substrate.core.JournalEntry`                 | `org.jwcarman.substrate.journal.JournalEntry`           |
| `org.jwcarman.substrate.core.Mailbox`                      | `org.jwcarman.substrate.mailbox.Mailbox`                |
| `org.jwcarman.substrate.core.MailboxFactory` *(becomes interface)* | `org.jwcarman.substrate.mailbox.MailboxFactory`         |
| *(new)* `MailboxExpiredException`                          | `org.jwcarman.substrate.mailbox.MailboxExpiredException`|

`JournalFactory` and `MailboxFactory` currently exist as concrete classes
in `substrate-core`. The *interfaces* go into `substrate-api` under the
names above; the *implementations* become `DefaultJournalFactory` and
`DefaultMailboxFactory` in `substrate-core` (see next section).

### `substrate-core` — implementations, SPIs, in-memory, autoconfig

| From                                                       | To                                                           |
|---|---|
| `org.jwcarman.substrate.core.DefaultJournal`               | `org.jwcarman.substrate.core.journal.DefaultJournal`         |
| `org.jwcarman.substrate.core.DefaultJournalCursor`         | `org.jwcarman.substrate.core.journal.DefaultJournalCursor`   |
| *(new)* `DefaultJournalFactory`                            | `org.jwcarman.substrate.core.journal.DefaultJournalFactory`  |
| `org.jwcarman.substrate.spi.JournalSpi`                    | `org.jwcarman.substrate.core.journal.JournalSpi`             |
| `org.jwcarman.substrate.spi.AbstractJournalSpi`            | `org.jwcarman.substrate.core.journal.AbstractJournalSpi`     |
| `org.jwcarman.substrate.spi.RawJournalEntry`               | `org.jwcarman.substrate.core.journal.RawJournalEntry`        |
| `org.jwcarman.substrate.memory.InMemoryJournalSpi`         | `org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi` |
| `org.jwcarman.substrate.core.DefaultMailbox`               | `org.jwcarman.substrate.core.mailbox.DefaultMailbox`         |
| *(new)* `DefaultMailboxFactory`                            | `org.jwcarman.substrate.core.mailbox.DefaultMailboxFactory`  |
| `org.jwcarman.substrate.spi.MailboxSpi`                    | `org.jwcarman.substrate.core.mailbox.MailboxSpi`             |
| `org.jwcarman.substrate.spi.AbstractMailboxSpi`            | `org.jwcarman.substrate.core.mailbox.AbstractMailboxSpi`     |
| `org.jwcarman.substrate.memory.InMemoryMailboxSpi`         | `org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi` |
| `org.jwcarman.substrate.spi.Notifier` *(renamed)*          | `org.jwcarman.substrate.core.notifier.NotifierSpi`           |
| `org.jwcarman.substrate.spi.NotificationHandler`           | `org.jwcarman.substrate.core.notifier.NotificationHandler`   |
| `org.jwcarman.substrate.spi.NotifierSubscription`          | `org.jwcarman.substrate.core.notifier.NotifierSubscription`  |
| `org.jwcarman.substrate.memory.InMemoryNotifier`           | `org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier` |
| `org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration` | `org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration` |
| `org.jwcarman.substrate.autoconfigure.SubstrateProperties` | `org.jwcarman.substrate.core.autoconfigure.SubstrateProperties` |

### Atom migration (from spec 018)

Spec 018 created Atom files directly in the new layout, so **nothing
moves** for Atom in this spec. Existing Atom files stay where 018 put
them.

However, spec 018 left `SubstrateAutoConfiguration` in its current
location (`org.jwcarman.substrate.autoconfigure`). This spec moves it to
`org.jwcarman.substrate.core.autoconfigure` along with everything else,
and updates the `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
file to reference the new fully-qualified class name.

### Backend modules

Every existing backend module has implementations that import from
`org.jwcarman.substrate.spi.*`. All of those imports need updating to
the new package locations. The backend module packages themselves
(e.g., `org.jwcarman.substrate.notifier.redis`) stay in place — spec
020 handles the backend-side module consolidation and package reorg.

Backend modules affected (by SPI):

- `JournalSpi` implementations: all `substrate-journal-*` modules
- `MailboxSpi` implementations: all `substrate-mailbox-*` modules
- `Notifier` implementations (now `NotifierSpi`): all `substrate-notifier-*` modules

Each backend implementation class needs:

1. Import updates (e.g., `org.jwcarman.substrate.spi.JournalSpi` →
   `org.jwcarman.substrate.core.journal.JournalSpi`).
2. If the class implements the notifier, update `implements Notifier`
   to `implements NotifierSpi`.
3. Remove any override of `Journal.append(T data)` no-TTL method (it
   no longer exists on the SPI — see API cleanup below).

## API changes

### Journal

Remove the no-TTL overload from both the user-facing interface and the
SPI.

**`org.jwcarman.substrate.journal.Journal<T>`** (in `substrate-api`):

```java
public interface Journal<T> {
  // REMOVED: String append(T data);
  String append(T data, Duration ttl);
  // ... remaining methods unchanged ...
}
```

**`org.jwcarman.substrate.core.journal.JournalSpi`** (in `substrate-core`):

```java
public interface JournalSpi {
  // REMOVED: String append(String key, byte[] data);
  String append(String key, byte[] data, Duration ttl);
  // ... remaining methods unchanged ...
}
```

**`InMemoryJournalSpi`** currently accepts `ttl` and **ignores it**.
This spec fixes that — the in-memory implementation must actually
enforce the TTL (entries expire after the duration; `readAfter` /
`readLast` skip expired entries; a lazy sweep on read keeps the store
bounded).

All backend `JournalSpi` implementations drop the no-TTL override.

### JournalFactory

Converts from a concrete class to an interface. The interface goes in
`substrate-api`, implementation in `substrate-core`.

**`org.jwcarman.substrate.journal.JournalFactory`** (in `substrate-api`):

```java
package org.jwcarman.substrate.journal;

import org.jwcarman.codec.spi.TypeRef;

public interface JournalFactory {
  <T> Journal<T> create(String name, Class<T> type);
  <T> Journal<T> create(String name, TypeRef<T> typeRef);
}
```

**`org.jwcarman.substrate.core.journal.DefaultJournalFactory`** (in
`substrate-core`) takes the exact same constructor shape as the
existing `JournalFactory` class does today — SPI + CodecFactory +
NotifierSpi — and implements `JournalFactory`.

### Mailbox — creation-time TTL

Mailboxes are one-shot. TTL attaches to the **mailbox**, not to each
delivery, because there's only one delivery per mailbox.

**`org.jwcarman.substrate.mailbox.Mailbox<T>`** (in `substrate-api`) —
unchanged method signatures, but semantics change:

- `deliver(T value)` — throws `MailboxExpiredException` if the mailbox
  is dead (TTL lapsed or explicitly deleted) at call time.
- `poll(Duration timeout)` — returns `Optional.empty()` only on actual
  polling timeout while the mailbox is still alive. Throws
  `MailboxExpiredException` if the mailbox is dead at call time or
  dies during the wait.
- `delete()` — unchanged.
- `key()` — unchanged.

**`org.jwcarman.substrate.mailbox.MailboxFactory`** (in `substrate-api`,
now an interface):

```java
package org.jwcarman.substrate.mailbox;

import java.time.Duration;
import org.jwcarman.codec.spi.TypeRef;

public interface MailboxFactory {
  <T> Mailbox<T> create(String name, Class<T> type, Duration ttl);
  <T> Mailbox<T> create(String name, TypeRef<T> typeRef, Duration ttl);
}
```

Note: no `connect` method for Mailbox. Mailboxes are typically
single-owner (one process creates, the same or another process delivers,
then poll consumes). If cross-process mailbox reattach becomes a real
requirement, add it as a follow-on spec.

**`org.jwcarman.substrate.mailbox.MailboxExpiredException`** (new, in
`substrate-api`):

```java
package org.jwcarman.substrate.mailbox;

/** Thrown when an operation targets a mailbox that has expired or been deleted. */
public class MailboxExpiredException extends RuntimeException {
  public MailboxExpiredException(String key) {
    super("Mailbox has expired or been deleted: " + key);
  }
}
```

### MailboxSpi changes

The current `MailboxSpi.deliver(key, value)` has no TTL awareness —
the backend just stores the value indefinitely. For creation-time TTL
to work, the SPI needs a reservation step.

**New `MailboxSpi` surface** (in
`org.jwcarman.substrate.core.mailbox.MailboxSpi`):

```java
public interface MailboxSpi {

  /**
   * Create a new mailbox with the given TTL. The mailbox is "empty" until
   * a deliver call lands. The backend must record enough state to tell
   * apart "not yet delivered" from "expired".
   *
   * @throws MailboxExpiredException if the backend operation fails because
   *         a previously-expired record was being cleaned up at the moment
   *         of creation. Normal creation on a fresh key just succeeds.
   */
  void create(String key, Duration ttl);

  /**
   * Deliver a value to a previously-created mailbox. Does not change the
   * mailbox's expiration time — the TTL was set at create time.
   *
   * @throws MailboxExpiredException if the mailbox no longer exists
   */
  void deliver(String key, byte[] value);

  /**
   * Read the current mailbox state.
   *
   * @return empty Optional if the mailbox exists but no value has been
   *         delivered yet, a present value if delivered, or throws
   *         MailboxExpiredException if the mailbox is dead
   */
  Optional<byte[]> get(String key);

  void delete(String key);

  String mailboxKey(String name);
}
```

The semantics of `get` need a third state (alive-but-empty vs. alive-with-value
vs. dead). The chosen encoding: `get` returns `Optional<byte[]>` where:

- `Optional.empty()` means the mailbox exists but has no delivered value
  yet (the caller should continue polling).
- `Optional.of(bytes)` means the mailbox exists and has a value.
- `throws MailboxExpiredException` means the mailbox is dead — no more
  polling will help.

`DefaultMailbox.poll` converts these into its own contract:

- `Optional.of(value)` when delivery has happened.
- `Optional.empty()` on polling timeout (mailbox still alive, no value
  yet within the timeout window).
- `throws MailboxExpiredException` when the SPI says the mailbox is
  dead.

### MailboxFactory flow

`DefaultMailboxFactory.create(name, type, ttl)` must:

1. Compute the backend key via `mailboxSpi.mailboxKey(name)`.
2. Call `mailboxSpi.create(key, ttl)` to establish the reservation.
3. Construct and return a `DefaultMailbox<T>` wrapping the key, codec,
   SPI, and notifier.

This makes `MailboxFactory.create` **eager** (it hits the backend
immediately), matching `AtomFactory.create`. That's a change from the
current behavior where `MailboxFactory.create` was a pure local handle
constructor.

### InMemoryMailboxSpi changes

The current in-memory implementation stores `ConcurrentMap<String, byte[]>`.
It needs an extra dimension to track the "alive but empty" state and the
expiration time:

```java
private record Entry(Optional<byte[]> value, Instant expiresAt) {
  boolean isAlive(Instant now) { return now.isBefore(expiresAt); }
}

private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

@Override
public void create(String key, Duration ttl) {
  store.put(key, new Entry(Optional.empty(), Instant.now().plus(ttl)));
}

@Override
public void deliver(String key, byte[] value) {
  Instant now = Instant.now();
  Entry updated = store.compute(key, (k, existing) -> {
    if (existing == null || !existing.isAlive(now)) {
      return null;   // dead or never created
    }
    return new Entry(Optional.of(value), existing.expiresAt());
  });
  if (updated == null) {
    throw new MailboxExpiredException(key);
  }
}

@Override
public Optional<byte[]> get(String key) {
  Entry entry = store.get(key);
  Instant now = Instant.now();
  if (entry == null || !entry.isAlive(now)) {
    if (entry != null) store.remove(key, entry);
    throw new MailboxExpiredException(key);
  }
  return entry.value();
}
```

### Notifier rename

`Notifier` → `NotifierSpi`. Every reference site in core + all backend
modules needs to update the type name. This is a mechanical rename.

Notification-related types that keep their names but move:

- `NotificationHandler` — moves to `org.jwcarman.substrate.core.notifier`
- `NotifierSubscription` — moves to `org.jwcarman.substrate.core.notifier`

## Max-TTL enforcement

A new configurable maximum TTL per primitive type, enforced in the
core layer with sensible defaults. Rejected requests throw
`IllegalArgumentException` with a clear message naming the primitive
and the configured max.

### Configuration properties

Add to `SubstrateProperties` (in its new location
`org.jwcarman.substrate.core.autoconfigure.SubstrateProperties`):

```java
package org.jwcarman.substrate.core.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "substrate")
public record SubstrateProperties(
    AtomProperties atom,
    JournalProperties journal,
    MailboxProperties mailbox) {

  public SubstrateProperties {
    if (atom == null) atom = new AtomProperties(Duration.ofHours(24));
    if (journal == null) journal = new JournalProperties(Duration.ofDays(7));
    if (mailbox == null) mailbox = new MailboxProperties(Duration.ofMinutes(30));
  }

  public record AtomProperties(Duration maxTtl) {}
  public record JournalProperties(Duration maxTtl) {}
  public record MailboxProperties(Duration maxTtl) {}
}
```

Defaults (set in code as shown above, also documented in
`substrate-defaults.properties`):

- `substrate.atom.max-ttl = 24h`
- `substrate.journal.max-ttl = 7d`
- `substrate.mailbox.max-ttl = 30m`

### Enforcement points

Core layer checks TTL against the configured max before any SPI call.
Reject with a clear exception:

```java
// DefaultAtomFactory.create and DefaultAtom.set
if (ttl.compareTo(maxAtomTtl) > 0) {
  throw new IllegalArgumentException(
      "Atom TTL " + ttl + " exceeds configured maximum " + maxAtomTtl);
}

// DefaultJournal.append
if (ttl.compareTo(maxJournalTtl) > 0) { ... }

// DefaultMailboxFactory.create
if (ttl.compareTo(maxMailboxTtl) > 0) { ... }
```

The max is injected into `DefaultAtomFactory`, `DefaultJournal`, and
`DefaultMailboxFactory` via constructor from `SubstrateProperties`.
Auto-configuration wires this up.

### Auto-configuration changes

`SubstrateAutoConfiguration` now passes the max-TTL values into
constructors:

```java
@Bean
@ConditionalOnBean({AtomSpi.class, CodecFactory.class, NotifierSpi.class})
public AtomFactory atomFactory(
    AtomSpi atomSpi,
    CodecFactory codecFactory,
    NotifierSpi notifier,
    SubstrateProperties properties) {
  return new DefaultAtomFactory(
      atomSpi, codecFactory, notifier, properties.atom().maxTtl());
}
```

Similar for `JournalFactory` and `MailboxFactory`.

The in-memory SPI fallback beans don't need the max-TTL — it's enforced
above them, at the `Default*Factory` layer, before any SPI call.

### `touch` interaction

`Atom.touch(Duration ttl)` — is the renewal also subject to max-TTL? Yes.
If a caller calls `touch(Duration.ofHours(48))` with a 24h max, it throws
`IllegalArgumentException` the same way `set` does. Consistency matters
more than flexibility here.

## PRD.md updates

The PRD.md file at the repository root needs three updates:

1. **Remove the constraint** `"Backend auto-configurations use
   @ConditionalOnClass only — no @ConditionalOnProperty"`. Per-primitive
   `@ConditionalOnProperty` switches are coming in spec 020, and this
   constraint explicitly forbids them.
2. **Add a "Primitives" subsection** under the SPIs section documenting
   `Atom` alongside `Journal`, `Mailbox`, and `Notifier` (well —
   `NotifierSpi` now). Include a one-paragraph description and the
   MCP-session use case.
3. **Update the module structure** section to reflect the
   `substrate-api` + `substrate-core` split. Don't preemptively
   document the backend module consolidation — that's spec 020's job.

Keep PRD.md changes tight. Don't rewrite sections that don't need
rewriting.

## Acceptance criteria

### Migration sanity

- [ ] `substrate-api` contains all user-facing interfaces: `Atom`,
      `AtomFactory`, `Snapshot`, `AtomExpiredException`,
      `AtomAlreadyExistsException`, `Journal`, `JournalFactory`,
      `JournalCursor`, `JournalEntry`, `Mailbox`, `MailboxFactory`,
      `MailboxExpiredException`.
- [ ] `substrate-api` has no dependencies on `substrate-core` or any
      backend module. Verified by running `./mvnw dependency:tree -pl
      substrate-api`.
- [ ] No class in `substrate-core` remains under the old packages
      `org.jwcarman.substrate.core`, `org.jwcarman.substrate.spi`,
      `org.jwcarman.substrate.memory`, or
      `org.jwcarman.substrate.autoconfigure`. All have been moved to
      their new `org.jwcarman.substrate.core.*` locations.
- [ ] The `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
      file in `substrate-core` references
      `org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration`.

### Factory interfaces

- [ ] `JournalFactory` is an interface in `substrate-api`.
- [ ] `DefaultJournalFactory` implements `JournalFactory`, lives in
      `substrate-core` at `org.jwcarman.substrate.core.journal`.
- [ ] `MailboxFactory` is an interface in `substrate-api`.
- [ ] `DefaultMailboxFactory` implements `MailboxFactory`, lives in
      `substrate-core` at `org.jwcarman.substrate.core.mailbox`.

### Journal cleanup

- [ ] `Journal.append(T data)` (no-TTL overload) is removed from
      `substrate-api/.../journal/Journal.java`.
- [ ] `JournalSpi.append(String, byte[])` (no-TTL overload) is removed
      from the SPI interface and all implementations (backend modules).
- [ ] `InMemoryJournalSpi` now actually enforces TTL: an entry written
      with `Duration.ofMillis(50)` is absent from `readAfter` /
      `readLast` after the TTL elapses. Verified with Awaitility.
- [ ] All existing tests that call `journal.append(value)` are updated
      to pass an explicit TTL.

### Mailbox cleanup

- [ ] `MailboxFactory.create` requires `Duration ttl`.
- [ ] `DefaultMailboxFactory.create` calls `mailboxSpi.create(key, ttl)`
      eagerly at factory-call time.
- [ ] `MailboxSpi` has a new `create(String, Duration)` method.
- [ ] `MailboxSpi.get` returns `Optional<byte[]>` where empty means
      "alive but no delivery yet," present means "delivered," and
      `MailboxExpiredException` means "dead."
- [ ] `Mailbox.deliver` throws `MailboxExpiredException` if the mailbox
      is dead.
- [ ] `Mailbox.poll` throws `MailboxExpiredException` if the mailbox is
      dead; returns `Optional.empty()` only on polling timeout while
      the mailbox is still alive.
- [ ] `InMemoryMailboxSpi` enforces TTL and the new three-state `get`
      semantics.
- [ ] All existing tests that call `MailboxFactory.create(name, type)`
      are updated to pass an explicit TTL.
- [ ] A test verifies that calling `deliver` on a TTL-expired mailbox
      throws `MailboxExpiredException` (using Awaitility).

### Notifier rename

- [ ] `NotifierSpi` exists at
      `org.jwcarman.substrate.core.notifier.NotifierSpi`.
- [ ] No code references `org.jwcarman.substrate.spi.Notifier` (the
      old name) anywhere in the repo.
- [ ] All backend `*Notifier` implementations implement `NotifierSpi`.

### Max-TTL enforcement

- [ ] `SubstrateProperties` has `atom`, `journal`, `mailbox`
      subproperties each containing `maxTtl: Duration`.
- [ ] Defaults: atom = 24h, journal = 7d, mailbox = 30m.
- [ ] `DefaultAtomFactory.create` throws `IllegalArgumentException` if
      the passed TTL exceeds the configured atom max.
- [ ] `DefaultAtom.set` throws `IllegalArgumentException` if the passed
      TTL exceeds the configured atom max.
- [ ] `DefaultAtom.touch` throws `IllegalArgumentException` if the
      passed TTL exceeds the configured atom max.
- [ ] `DefaultJournal.append` throws `IllegalArgumentException` if the
      passed TTL exceeds the configured journal max.
- [ ] `DefaultMailboxFactory.create` throws `IllegalArgumentException`
      if the passed TTL exceeds the configured mailbox max.
- [ ] Overriding via `application.yml` (`substrate.atom.max-ttl: 1h`)
      works and is observed by the factory. Verified by an
      autoconfiguration test.

### PRD.md

- [ ] The `"@ConditionalOnClass only"` constraint is removed.
- [ ] A Primitives section (or equivalent) documents `Atom` alongside
      the existing primitives.
- [ ] The module structure section reflects `substrate-api` +
      `substrate-core`.

### Backend modules

- [ ] Every backend module compiles after the SPI package moves and
      the `Notifier` → `NotifierSpi` rename.
- [ ] Every backend `JournalSpi` implementation no longer overrides
      `append(String, byte[])` (the no-TTL method is gone).
- [ ] Every backend `MailboxSpi` implementation implements the new
      `create(String, Duration)` method and the new three-state `get`
      semantics.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers on every moved, renamed, and newly
      created file.
- [ ] No `@SuppressWarnings` annotations introduced.

## Implementation notes

- This spec is large by line count but mostly mechanical. The big
  chunks are:
  1. Move files + update packages (rote)
  2. Remove `Journal.append(T)` everywhere (rote)
  3. Add Mailbox creation-time TTL (design work in `MailboxSpi`)
  4. Rename `Notifier` → `NotifierSpi` (rote)
  5. Max-TTL config plumbing (small, touches a few files)
- Do the package migration first in one commit-sized chunk. Get the
  build green. Then do the API changes.
- When renaming `Notifier` → `NotifierSpi`, IDE refactoring tools will
  handle most of the work, but verify the text of error messages in
  the `SubstrateAutoConfiguration` WARN logs still mentions
  `NotifierSpi` (not `Notifier`).
- `MailboxSpi.create` is a real semantic change — every backend that
  previously used `PUT` / `INSERT` as the "first write" now needs to
  distinguish "create empty mailbox" from "deliver value." For Redis,
  this might mean storing a hash with fields `created_at` and
  `value`, where `value` is absent until deliver. Document the
  approach per backend in the follow-on spec 020 when those backends
  get consolidated.
- Existing integration tests in backend modules will break as the
  mailbox SPI changes. Update them as part of this spec — don't skip
  them (`-DskipITs`) in the CI verification.
- The `SubstrateProperties` changes need a
  `substrate-defaults.properties` update too, with the new default
  values documented.
- The `touch` max-TTL check means a caller can't "cheat around" the
  max by calling `set` with 24h and then `touch` with 48h every hour.
  That's a feature, not a bug — the max means max.
