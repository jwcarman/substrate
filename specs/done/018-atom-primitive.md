# Atom primitive: a leased, keyed reference with token-based staleness detection

## What to build

A new primitive `Atom<T>` — a keyed handle to a value that lives in a
backend under a mandatory TTL, can be updated in place, can have its lease
renewed without changing the value, and supports staleness detection via
opaque content-derived tokens.

This spec **also creates a new `substrate-api` Maven module**. All user-
facing interfaces and value types for Atom live in `substrate-api`;
implementations, SPIs, and the in-memory fallback live in `substrate-core`.
Spec 019 will retroactively migrate the existing `Journal`, `Mailbox`, and
`Notifier` types into the same two-module layout.

### Module layout after this spec

```
substrate-api/                                      [NEW MODULE]
  pom.xml
  src/main/java/org/jwcarman/substrate/atom/
    Atom.java                           (interface)
    AtomFactory.java                    (interface)
    Snapshot.java                       (record)
    AtomExpiredException.java
    AtomAlreadyExistsException.java

substrate-core/
  pom.xml                               [MODIFIED — add substrate-api dep]
  src/main/java/org/jwcarman/substrate/core/atom/          [NEW PACKAGE]
    DefaultAtom.java
    DefaultAtomFactory.java
    AtomSpi.java                        (SPI interface)
    AbstractAtomSpi.java
    AtomRecord.java                     (SPI record)
  src/main/java/org/jwcarman/substrate/core/memory/atom/   [NEW PACKAGE]
    InMemoryAtomSpi.java
  src/main/java/org/jwcarman/substrate/autoconfigure/
    SubstrateAutoConfiguration.java     [MODIFIED — add Atom wiring]
  src/test/java/org/jwcarman/substrate/core/atom/          [NEW PACKAGE]
    DefaultAtomTest.java
    DefaultAtomFactoryTest.java
  src/test/java/org/jwcarman/substrate/core/memory/atom/   [NEW PACKAGE]
    InMemoryAtomSpiTest.java

substrate-bom/
  pom.xml                               [MODIFIED — add substrate-api entry]

pom.xml (parent)                        [MODIFIED — add substrate-api module]

substrate-core/src/main/java/org/jwcarman/substrate/core/Atom.java  [DELETED]
```

### Module split rationale

- **`substrate-api`** is a tiny, stable module containing only user-facing
  interfaces and value types. Contributors writing applications against
  substrate only need to look here. It has minimal dependencies and should
  change slowly.
- **`substrate-core`** contains default implementations, SPIs, in-memory
  fallbacks, and autoconfiguration. Applications depend on `substrate-core`
  (which transitively pulls `substrate-api`). Backend modules
  (`substrate-redis`, etc., in future specs) depend only on `substrate-api`
  plus a new `substrate-spi` module or a narrow SPI surface — but that
  module split is a follow-on and out of scope for this spec. For now,
  backends can depend on `substrate-core` and accept the in-memory fallback
  classes as inert classpath dead weight.
- Packages never span modules: `substrate-api` owns
  `org.jwcarman.substrate.atom`, `substrate-core` owns
  `org.jwcarman.substrate.core.atom`. No split packages, JPMS-compatible
  if the project ever adopts Java modules.

### Scope boundary

Spec 018 only touches Atom. `Journal`, `Mailbox`, and `Notifier` keep their
current package locations (`org.jwcarman.substrate.core.*`,
`org.jwcarman.substrate.spi.*`, `org.jwcarman.substrate.memory.*`) until
spec 019 migrates them. `SubstrateAutoConfiguration` is modified in place
(to add Atom wiring) but not moved; spec 019 will move it to
`org.jwcarman.substrate.core.autoconfigure` as part of the sweep.

### Deferred to spec 019

The following are intentionally **not** part of spec 018, to keep it
focused:

- Removing `Journal.append(T data)` no-TTL overload
- Converting `MailboxFactory.create` to require a creation-time `Duration
  ttl` (mailboxes are one-shot — TTL attaches to the mailbox, not to
  each delivery)
- Introducing `MailboxExpiredException`
- Converting `Notifier` to `NotifierSpi` and moving it to
  `org.jwcarman.substrate.core.notifier`
- Making `MailboxFactory` / `JournalFactory` interfaces with `Default*`
  implementations (today they're concrete classes)
- Migrating existing `Journal` / `Mailbox` / memory / autoconfigure
  packages into the new two-module layout
- Max-TTL enforcement per primitive type (configurable max with sensible
  defaults, enforced in the core layer)

### Deferred to spec 021

- Enforcing single-delivery on `Mailbox` (throw `MailboxFullException`
  on a second `deliver` call)

## Motivation

Substrate needs a primitive for **parking a named, keyed value across
requests** that can be renewed, queried, and (optionally) watched for
changes. The motivating use case is MCP session state: a session is
created once, parked under a stable key, its lease is refreshed on
activity, and any process holding the session id can reattach to the
same live state.

Existing primitives don't fit:

- **`Mailbox`** is a one-shot slot — it's `CompletableFuture`-shaped.
  A mailbox with no delivery is the normal start state; a delivered
  mailbox is terminal. That's the wrong shape for "a value that stays
  around and mutates."
- **`Journal`** is an append-only log. Readers see history. Atoms want
  *current value only*, with no exposure of prior versions.

`Atom<T>` fills the gap: a keyed handle to a live value that exists
right now, has a lease, can be updated in place, and offers staleness
detection to callers that want to react to changes.

## The "intentionally leased" tenet

Atom is designed around a project-wide tenet:

> **Every substrate primitive is intentionally leased. TTL is not
> optional on any write.**

For `Atom`, this means `create` and `set` both require a `Duration ttl`
argument and there is no no-TTL overload. Callers who need effectively-
permanent state pass a large duration explicitly. There is no escape
hatch, and that's the point — the lifetime is visible at the call site.

Spec 018 only applies this tenet to Atom, which is greenfield. Spec 019
will apply it retroactively to `Journal` (remove the `append(T)` no-TTL
overload) and `Mailbox` (attach TTL at factory creation time, since a
mailbox is one-shot).

## Atom semantics

An `Atom<T>` is a handle to a keyed reference value in the backend. The
atom exists iff its backend record has not expired or been deleted.
Unlike `Mailbox`, an atom that exists **always has a value** — there is
no "present-but-empty" state. Unlike `Journal`, only the current value
is visible — there is no history.

Lifecycle:

1. **`create`** — writes an initial value and a TTL to the backend.
   Fails if an atom with the same name already exists.
2. **`set`** — overwrites the current value and resets the lease.
   Generates a new staleness token.
3. **`touch`** — renews the lease (extends TTL) without changing the
   value. Does not generate a new token. Does not wake watchers.
4. **`get`** — reads the current value and its token.
5. **`watch`** — blocks until the token has advanced past the caller's
   last-seen token, or until timeout.
6. **`delete`** — explicitly removes the atom.
7. **TTL expiry** — the backend silently drops the record; subsequent
   operations on the handle treat the atom as dead.

Once an atom is dead (expired or deleted), it is gone — not "empty." Any
`get`/`watch`/`set` on a dead atom throws `AtomExpiredException`. `touch`
returns `false` on a dead atom (so callers who were trying to renew a
lease can fall back to re-creating the atom without a try/catch). Dead
atoms do not come back to life; creating a new atom with the same name
produces a logically distinct atom with a fresh token lineage.

### Staleness tokens

Every stored value is accompanied by an opaque **token** — a string that
lets callers detect "has this value been replaced since I last read it?"
Tokens are:

- **Used for staleness detection only.** Not version numbers, not
  sequence numbers, cannot be used to access prior values. No history.
- **Opaque to callers.** Callers compare tokens for equality and
  nothing else.
- **Bumped on `set`.** Every write produces a new token.
- **Not bumped on `touch`.** Lease renewals leave the token unchanged so
  watchers don't wake on spurious liveness signals.
- **Content-derived** (see "Token scheme" below). Two sets with
  identical encoded bytes produce the same token — a deliberate
  idempotency property.

### Watch semantics

`watch(Snapshot<T> lastSeen, Duration timeout)` has three outcomes:

1. **Atom's current token ≠ `lastSeen.token()`.** Return a fresh
   `Snapshot<T>` immediately (the caller was already stale).
2. **Atom's current token == `lastSeen.token()`.** Block until either:
   - someone calls `set` (token advances) → return the new snapshot, or
   - timeout elapses → return `Optional.empty()`.
3. **Atom is dead (expired or deleted) at call time or dies during the
   wait.** Throw `AtomExpiredException`. Watchers get a clear terminal
   signal rather than polling forever on `Optional.empty()`.

First-time watchers who have never read can pass `null` as `lastSeen`,
matching "no prior snapshot, show me current or wait for first."

## API types (substrate-api)

### `Atom<T>`

```java
package org.jwcarman.substrate.atom;

import java.time.Duration;
import java.util.Optional;

public interface Atom<T> {

  /**
   * Overwrite the current value and reset the lease.
   *
   * Generates a new staleness token and publishes a notification so any
   * blocked watchers wake up.
   *
   * @throws AtomExpiredException if the atom has already expired or been deleted
   */
  void set(T data, Duration ttl);

  /**
   * Renew the lease without changing the value.
   *
   * Does not bump the staleness token. Does not wake watchers.
   *
   * @return true if the lease was renewed; false if the atom was already
   *         dead (expired or deleted) at the moment of the call
   */
  boolean touch(Duration ttl);

  /**
   * Read the current value and its staleness token.
   *
   * @throws AtomExpiredException if the atom has expired or been deleted
   */
  Snapshot<T> get();

  /**
   * Block until the atom's current token differs from {@code lastSeen.token()},
   * or until {@code timeout} elapses.
   *
   * @param lastSeen the caller's previously observed snapshot; may be null to
   *        mean "I have no prior snapshot — return current or wait for first"
   * @param timeout how long to wait for a change
   * @return a fresh snapshot if the atom has advanced; empty if the timeout
   *         elapsed with no change
   * @throws AtomExpiredException if the atom is dead at call time or dies
   *         during the wait
   */
  Optional<Snapshot<T>> watch(Snapshot<T> lastSeen, Duration timeout);

  /** Explicitly remove the atom. Subsequent operations see it as dead. */
  void delete();

  /** The backend-qualified key for this atom. */
  String key();
}
```

### `AtomFactory`

```java
package org.jwcarman.substrate.atom;

import java.time.Duration;
import org.jwcarman.codec.spi.TypeRef;

public interface AtomFactory {

  /**
   * Create a new atom with an initial value.
   *
   * Eager: writes {@code initialValue} and its token to the backend before
   * returning.
   *
   * @throws AtomAlreadyExistsException if a live atom already exists with this name
   */
  <T> Atom<T> create(String name, Class<T> type, T initialValue, Duration ttl);
  <T> Atom<T> create(String name, TypeRef<T> typeRef, T initialValue, Duration ttl);

  /**
   * Reattach to an existing atom.
   *
   * Lazy: performs no backend I/O. The returned handle is a local object
   * bundling the name, codec, and SPI reference. Existence is discovered
   * on the first operation — {@link Atom#get()}, {@link Atom#set},
   * {@link Atom#touch}, or {@link Atom#watch} will throw
   * {@link AtomExpiredException} if no live atom exists at this name.
   */
  <T> Atom<T> connect(String name, Class<T> type);
  <T> Atom<T> connect(String name, TypeRef<T> typeRef);
}
```

### `Snapshot<T>`

```java
package org.jwcarman.substrate.atom;

/**
 * A point-in-time view of an atom's value with its staleness token.
 * Immutable; safe to hold indefinitely as a reference point for future
 * staleness checks.
 */
public record Snapshot<T>(T value, String token) {}
```

### Exceptions

```java
package org.jwcarman.substrate.atom;

/** Thrown when an operation targets an atom that has expired or been deleted. */
public class AtomExpiredException extends RuntimeException {
  public AtomExpiredException(String key) {
    super("Atom has expired or been deleted: " + key);
  }
}
```

```java
package org.jwcarman.substrate.atom;

/** Thrown when {@link AtomFactory#create} is called on a name that already has a live atom. */
public class AtomAlreadyExistsException extends RuntimeException {
  public AtomAlreadyExistsException(String key) {
    super("Atom already exists: " + key);
  }
}
```

## Implementation types (substrate-core)

### `DefaultAtomFactory`

```java
package org.jwcarman.substrate.core.atom;

import java.time.Duration;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomFactory;
import org.jwcarman.substrate.spi.Notifier;

public class DefaultAtomFactory implements AtomFactory {

  private final AtomSpi atomSpi;
  private final CodecFactory codecFactory;
  private final Notifier notifier;

  public DefaultAtomFactory(AtomSpi atomSpi, CodecFactory codecFactory, Notifier notifier) {
    this.atomSpi = atomSpi;
    this.codecFactory = codecFactory;
    this.notifier = notifier;
  }

  @Override
  public <T> Atom<T> create(String name, Class<T> type, T initialValue, Duration ttl) {
    Codec<T> codec = codecFactory.create(type);
    String key = atomSpi.atomKey(name);
    byte[] bytes = codec.encode(initialValue);
    String token = DefaultAtom.token(bytes);
    atomSpi.create(key, bytes, token, ttl);   // throws AtomAlreadyExistsException
    notifier.notify(key, token);
    return new DefaultAtom<>(atomSpi, key, codec, notifier);
  }

  @Override
  public <T> Atom<T> create(String name, TypeRef<T> typeRef, T initialValue, Duration ttl) {
    Codec<T> codec = codecFactory.create(typeRef);
    String key = atomSpi.atomKey(name);
    byte[] bytes = codec.encode(initialValue);
    String token = DefaultAtom.token(bytes);
    atomSpi.create(key, bytes, token, ttl);
    notifier.notify(key, token);
    return new DefaultAtom<>(atomSpi, key, codec, notifier);
  }

  @Override
  public <T> Atom<T> connect(String name, Class<T> type) {
    Codec<T> codec = codecFactory.create(type);
    String key = atomSpi.atomKey(name);
    return new DefaultAtom<>(atomSpi, key, codec, notifier);
    // No backend I/O — existence is discovered on the first operation.
  }

  @Override
  public <T> Atom<T> connect(String name, TypeRef<T> typeRef) {
    Codec<T> codec = codecFactory.create(typeRef);
    String key = atomSpi.atomKey(name);
    return new DefaultAtom<>(atomSpi, key, codec, notifier);
  }
}
```

### `DefaultAtom<T>`

```java
package org.jwcarman.substrate.core.atom;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.atom.Snapshot;
import org.jwcarman.substrate.spi.Notifier;

public class DefaultAtom<T> implements Atom<T> {

  private final AtomSpi atomSpi;
  private final String key;
  private final Codec<T> codec;
  private final Notifier notifier;

  public DefaultAtom(AtomSpi atomSpi, String key, Codec<T> codec, Notifier notifier) {
    this.atomSpi = atomSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
  }

  @Override
  public void set(T data, Duration ttl) {
    byte[] bytes = codec.encode(data);
    String token = token(bytes);
    boolean alive = atomSpi.set(key, bytes, token, ttl);
    if (!alive) {
      throw new AtomExpiredException(key);
    }
    notifier.notify(key, token);
  }

  @Override
  public boolean touch(Duration ttl) {
    return atomSpi.touch(key, ttl);
  }

  @Override
  public Snapshot<T> get() {
    AtomRecord record = atomSpi.read(key)
        .orElseThrow(() -> new AtomExpiredException(key));
    return new Snapshot<>(codec.decode(record.value()), record.token());
  }

  @Override
  public Optional<Snapshot<T>> watch(Snapshot<T> lastSeen, Duration timeout) {
    // See "Watch implementation" below for the full pattern.
  }

  @Override
  public void delete() {
    atomSpi.delete(key);
  }

  @Override
  public String key() {
    return key;
  }

  static String token(byte[] encodedBytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedBytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
```

### `AtomSpi`

```java
package org.jwcarman.substrate.core.atom;

import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;

public interface AtomSpi {

  /**
   * Write a new atom. Must be atomic "set-if-not-exists" at the backend level.
   *
   * @throws AtomAlreadyExistsException if a live atom already exists at this key
   */
  void create(String key, byte[] value, String token, Duration ttl);

  /** Read the current record, or empty if the atom is expired / absent. */
  Optional<AtomRecord> read(String key);

  /**
   * Overwrite the current record and reset the TTL.
   *
   * @return true if the atom was alive and was updated;
   *         false if the atom was already dead at the moment of the call
   */
  boolean set(String key, byte[] value, String token, Duration ttl);

  /**
   * Extend the TTL of an existing atom without changing its value or token.
   *
   * @return true if the lease was renewed; false if the atom was already dead
   */
  boolean touch(String key, Duration ttl);

  /** Remove the atom. No-op if already absent. */
  void delete(String key);

  /** Backend-qualified key for the given user-facing name. */
  String atomKey(String name);
}
```

### `AtomRecord`

```java
package org.jwcarman.substrate.core.atom;

/** An opaque (value, token) tuple as stored in the backend. */
public record AtomRecord(byte[] value, String token) {}
```

### `AbstractAtomSpi`

```java
package org.jwcarman.substrate.core.atom;

/**
 * Shared prefix helper for {@link AtomSpi} implementations, matching the
 * existing {@code AbstractJournalSpi} / {@code AbstractMailboxSpi} pattern.
 */
public abstract class AbstractAtomSpi implements AtomSpi {

  private final String prefix;

  protected AbstractAtomSpi(String prefix) {
    this.prefix = prefix;
  }

  protected String prefix() {
    return prefix;
  }

  @Override
  public String atomKey(String name) {
    return prefix + name;
  }
}
```

### `InMemoryAtomSpi`

Lives in `org.jwcarman.substrate.core.memory.atom`. Must enforce TTL
(unlike the current `InMemoryJournalSpi`, which accepts a TTL parameter
but ignores it — that's a known gap that spec 019 also fixes).

Sketch:

```java
package org.jwcarman.substrate.core.memory.atom;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.AtomRecord;

public class InMemoryAtomSpi extends AbstractAtomSpi {

  private record Entry(byte[] value, String token, Instant expiresAt) {
    boolean isAlive(Instant now) {
      return now.isBefore(expiresAt);
    }
  }

  private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

  public InMemoryAtomSpi() {
    super("substrate:atom:");
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    Entry entry = new Entry(value, token, Instant.now().plus(ttl));
    Entry previous = store.compute(key, (k, existing) -> {
      if (existing != null && existing.isAlive(Instant.now())) {
        return existing;   // keep the existing live entry; signal failure below
      }
      return entry;
    });
    if (previous != entry) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    Entry entry = store.get(key);
    if (entry == null) return Optional.empty();
    if (!entry.isAlive(Instant.now())) {
      store.remove(key, entry);   // lazy cleanup
      return Optional.empty();
    }
    return Optional.of(new AtomRecord(entry.value(), entry.token()));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    Instant now = Instant.now();
    Entry next = new Entry(value, token, now.plus(ttl));
    Entry result = store.compute(key, (k, existing) -> {
      if (existing == null || !existing.isAlive(now)) {
        return null;   // dead: don't create via set
      }
      return next;
    });
    return result == next;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    Instant now = Instant.now();
    Entry result = store.compute(key, (k, existing) -> {
      if (existing == null || !existing.isAlive(now)) {
        return null;
      }
      return new Entry(existing.value(), existing.token(), now.plus(ttl));
    });
    return result != null;
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }
}
```

Note: `set` intentionally does *not* create a new atom when called on a
dead key. The core layer raises `AtomExpiredException` when it sees
`false`. Callers who want "create or overwrite" must call `create`
explicitly, catch `AtomAlreadyExistsException`, and then call `set`.

### Watch implementation

Mirrors the `DefaultMailbox` reader-thread + semaphore pattern
established in spec 017:

- A `Semaphore(0)` and a `Notifier` subscription filtered by `key`.
- On subscription fire, the watcher thread wakes and re-reads
  `atomSpi.read`.
- If the record is absent → `AtomExpiredException`.
- If the record's token differs from `lastSeen.token()` → decode and
  return a fresh `Snapshot<T>`.
- Otherwise → block on the semaphore again until either the timeout
  elapses (return `Optional.empty()`) or another notification arrives.
- "Just in case" read before the first wait covers nudges that arrive
  before the subscription was registered.
- On return (any path), `subscription.cancel()` to clean up.

Because `Notifier.notify` payload already carries the new token, a
future optimization is possible: the watcher could compare the
notification payload to `lastSeen.token()` before hitting the backend,
and only re-read on a token difference. Deferred — the first
implementation always re-reads.

## Token scheme

A staleness token is the **URL-safe unpadded base64 encoding of the
SHA-256 digest of the codec-encoded bytes** of the atom's value.

```java
private static String token(byte[] encodedBytes) {
  byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedBytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
}
```

Produces 43-character tokens like
`47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU`, safe in URLs, JSON, log
lines, and all supported backend field types.

**Why content hash:** two `set` calls with identical encoded bytes
produce the same token, meaning idempotent re-writes don't wake
watchers. This falls naturally out of "the atom is a reference to a
value; if the value is the same, the reference is the same." Perfect
for MCP session parking, where a manager re-writing identical session
bytes should be a no-op for watchers. Users who want "notify me on
every write even if bytes are identical" should use `Journal`, not
`Atom`.

**Why SHA-256:** JDK-native (`MessageDigest.getInstance("SHA-256")`),
cryptographic collision probability is negligible, cost is microseconds
per write (negligible next to the network round-trip to the backend),
and 32-byte digests are small enough that storage cost is trivial
across all backends.

**Why URL-safe unpadded base64 over hex:** 43 chars vs. 64, saves 21
characters per token, readable in logs, avoids `+`/`/` characters that
are awkward in URLs and some field types. Matches the JWT convention.

**What the hash is computed over:** the codec-encoded `byte[]`, *not*
the original `T`. Two logically-equal `T` values that serialize to
different bytes correctly produce different tokens. Codec changes
(JSON → CBOR, say) invalidate all in-flight tokens, which is correct
because the on-the-wire representation really did change. Tokens are
ephemeral staleness markers and should not be persisted across codec
migrations.

## SPI atomicity contract

`AtomSpi.create` must be implemented as an **atomic "set-if-not-exists"**
at the backend level. Check-then-write is not acceptable — it has a
TOCTOU window that two concurrent `create` calls can slip through.

For `InMemoryAtomSpi`, this is handled by `ConcurrentHashMap.compute`,
which is atomic per-key.

For future backend implementations:

- **Redis:** `SET key value NX EX ttl` (with a hash or secondary key
  for the token)
- **PostgreSQL:** `INSERT ... ON CONFLICT (key) DO NOTHING RETURNING ...`
- **DynamoDB:** `PutItem` with
  `ConditionExpression: attribute_not_exists(key)`
- **MongoDB:** `insertOne` with a unique index, catch
  `DuplicateKeyException`
- **Hazelcast:** `IMap.putIfAbsent` or `tryLock` + check + put

`set` does not need set-if-not-exists semantics — it's a blind
overwrite-if-alive. It returns `false` (not throws) if the atom was
already dead, so the core layer can raise `AtomExpiredException`
uniformly.

## Autoconfiguration wiring

Modify `SubstrateAutoConfiguration` in its current location
(`org.jwcarman.substrate.autoconfigure` — spec 019 will move it).
Add two beans, following the existing pattern:

```java
@Bean
@ConditionalOnMissingBean(AtomSpi.class)
public InMemoryAtomSpi atomSpi() {
  log.warn(
      "No Atom implementation found; using in-memory fallback "
          + "(single-node only). For clustered deployments, add a backend "
          + "module (e.g. substrate-atom-redis).");
  return new InMemoryAtomSpi();
}

@Bean
@ConditionalOnBean({AtomSpi.class, CodecFactory.class, Notifier.class})
public AtomFactory atomFactory(
    AtomSpi atomSpi, CodecFactory codecFactory, Notifier notifier) {
  return new DefaultAtomFactory(atomSpi, codecFactory, notifier);
}
```

Add the necessary imports:

```java
import org.jwcarman.substrate.atom.AtomFactory;
import org.jwcarman.substrate.core.atom.AtomSpi;
import org.jwcarman.substrate.core.atom.DefaultAtomFactory;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
```

## Decisions

These were open during brainstorming and are now resolved for this spec:

1. **`touch` does not publish a `Notifier` notification, and does not
   bump the token.** Watchers care about value changes, not liveness
   signals. Only `set` publishes and bumps the token.
2. **`watch` always re-reads the backend after waking**, without
   inspecting the notification payload. Optimization deferred.
3. **The tenet's application to future coordination primitives** (locks,
   semaphores, counters) is out of scope. Decide for future primitives
   when they are designed.
4. **`Journal.append(T)` removal and `Mailbox` creation-time TTL** are
   handled in spec 019, not here.
5. **No convenience `watch(Duration timeout)` overload.** Callers with
   no prior snapshot pass `null` as `lastSeen`.
6. **Max-TTL enforcement deferred to spec 019.** Spec 018 accepts any
   non-null `Duration`; the max-TTL guard is a one-line addition later.
7. **`AtomFactory` is an interface in `substrate-api`** with
   `DefaultAtomFactory` in `substrate-core`. The existing
   `MailboxFactory` and `JournalFactory` (concrete classes) get
   converted to the same pattern in spec 019.
8. **`substrate-api` is created by this spec.** Spec 019 migrates
   existing primitives into it.

## Acceptance criteria

### New module

- [ ] A new Maven module `substrate-api` exists at the repository root.
- [ ] `substrate-api/pom.xml` declares the module with `packaging: jar`,
      inherits from `substrate-parent`, and has no dependencies on
      `substrate-core` or any backend module. Its only dependency is
      `codec-spi` (for `TypeRef` on the `AtomFactory` interface).
- [ ] The parent `pom.xml` lists `substrate-api` as a module, placed
      before `substrate-core` so that `substrate-core` can depend on it.
- [ ] `substrate-bom/pom.xml` has a new `<dependency>` entry for
      `substrate-api` with `${project.version}`.
- [ ] `substrate-core/pom.xml` has a new `<dependency>` entry for
      `substrate-api`.
- [ ] Apache 2.0 license headers on all new POM files.

### Files created in `substrate-api`

- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/atom/Atom.java`
- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/atom/AtomFactory.java`
      (interface)
- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/atom/Snapshot.java`
- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/atom/AtomExpiredException.java`
- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/atom/AtomAlreadyExistsException.java`

### Files created in `substrate-core`

- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtom.java`
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtomFactory.java`
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/AtomSpi.java`
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/AtomRecord.java`
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/AbstractAtomSpi.java`
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/memory/atom/InMemoryAtomSpi.java`

### Files deleted

- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/Atom.java`
      (the pre-existing stub). If the `.../core/` directory becomes
      empty, remove it.

### Files modified in `substrate-core`

- [ ] `SubstrateAutoConfiguration` registers an `InMemoryAtomSpi` bean
      via `@ConditionalOnMissingBean(AtomSpi.class)` with a WARN log on
      activation, matching the existing Journal/Mailbox/Notifier
      fallback pattern.
- [ ] `SubstrateAutoConfiguration` registers an `AtomFactory` bean via
      `@ConditionalOnBean({AtomSpi.class, CodecFactory.class,
      Notifier.class})` that returns a `DefaultAtomFactory`.

### Factory behavior

- [ ] `DefaultAtomFactory.create` writes the initial value and returns
      a plain `Atom<T>` (no wrapper type).
- [ ] `DefaultAtomFactory.create` throws `AtomAlreadyExistsException`
      on name collision. Verified by a concurrent-creation test (two
      threads call `create` with the same name; exactly one succeeds).
- [ ] `DefaultAtomFactory.connect` returns a plain `Atom<T>` and
      performs **zero** backend I/O at factory-call time. Verified by
      a test using a mock `AtomSpi` that fails any method call during
      the `connect` invocation.
- [ ] A `connect`ed handle throws `AtomExpiredException` on its first
      `get` / `set` / `watch` call if no live atom exists at the key.
- [ ] A `connect`ed handle's first `touch` returns `false` if no live
      atom exists at the key.

### Atom behavior

- [ ] `Atom.set` publishes a `Notifier` notification whose payload is
      the new staleness token.
- [ ] `Atom.set` throws `AtomExpiredException` if called on a dead
      atom.
- [ ] `Atom.touch` does **not** bump the token and does **not**
      publish a notification. Verified by a test that registers a
      notification handler, calls `touch`, and asserts no notification
      was delivered and that a subsequent `get` returns the same token
      as before.
- [ ] `Atom.touch` returns `false` if called on a dead atom.
- [ ] `Atom.get` throws `AtomExpiredException` if called on a dead
      atom.
- [ ] `Atom.watch(lastSeen, timeout)` returns a fresh snapshot
      immediately if the atom's current token differs from
      `lastSeen.token()`.
- [ ] `Atom.watch(null, timeout)` returns a fresh snapshot immediately
      if the atom exists.
- [ ] `Atom.watch(lastSeen, timeout)` blocks until a new `set` occurs
      and returns the new snapshot.
- [ ] `Atom.watch(lastSeen, timeout)` returns `Optional.empty()` when
      the timeout elapses with no change.
- [ ] `Atom.watch` throws `AtomExpiredException` if the atom is dead
      at call time or dies at any point during the wait.
- [ ] `Atom.delete()` removes the atom and subsequent operations on
      the handle treat it as dead.

### Tokens

- [ ] Tokens are computed as URL-safe, unpadded base64 encoding of
      the SHA-256 digest of the codec-encoded bytes. Verified by a
      unit test with a known input and the expected 43-character
      output.
- [ ] Two `set` calls with bytewise-identical encoded values produce
      identical tokens. Verified by a unit test.
- [ ] Codec lives in `DefaultAtom<T>`; `AtomSpi` has no generic
      parameters and no codec-related types in its signature.

### SPI contract

- [ ] `InMemoryAtomSpi.create` is atomic "set-if-not-exists" — two
      concurrent `create` calls on the same key result in exactly one
      success and one `AtomAlreadyExistsException`. Verified in
      `InMemoryAtomSpiTest` with a thread-pool test.
- [ ] `InMemoryAtomSpi` respects TTL — an atom written with
      `Duration.ofMillis(50)` is observably absent after a short wait
      (verified with Awaitility, not `Thread.sleep`).
- [ ] `InMemoryAtomSpi.touch` extends the lease — an atom written
      with a 50ms TTL and then `touch`ed with a 5s TTL is still
      present after 1 second (verified with Awaitility).
- [ ] `InMemoryAtomSpi.set` on a dead atom returns `false`.
- [ ] `InMemoryAtomSpi.touch` on a dead atom returns `false`.
- [ ] `InMemoryAtomSpi.read` on a dead atom returns `Optional.empty()`.

### Tests

- [ ] `substrate-core/src/test/java/org/jwcarman/substrate/core/atom/DefaultAtomTest.java`
      covers set/touch/get/watch/delete and all dead-atom transitions.
- [ ] `substrate-core/src/test/java/org/jwcarman/substrate/core/atom/DefaultAtomFactoryTest.java`
      covers create/connect including collision and lazy-connect
      behavior.
- [ ] `substrate-core/src/test/java/org/jwcarman/substrate/core/memory/atom/InMemoryAtomSpiTest.java`
      covers TTL expiry, touch renewal, and concurrent create.
- [ ] Tests use Awaitility for any time-sensitive assertions (no
      `Thread.sleep` in tests, per existing project convention).
- [ ] Existing tests all continue to pass.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`
- [ ] Apache 2.0 license headers present on every new Java and POM
      file.

## Implementation notes

- Mirror the existing layering: compare `DefaultMailbox`
  (substrate-core) + `MailboxSpi` (substrate-core/spi) +
  `InMemoryMailboxSpi` (substrate-core/memory). Same layering for
  Atom, modulo the package reorg.
- Reuse the reader-virtual-thread + semaphore pattern from spec 017
  for `watch`. Do not invent a new concurrency approach.
- Backend implementations (`substrate-atom-redis`,
  `substrate-atom-postgresql`, etc.) are out of scope for this spec.
  They'll be follow-on specs after backend module consolidation
  (spec 020) lands.
- When adding the Atom imports to `SubstrateAutoConfiguration`, place
  them alphabetically with the existing imports. Don't reorganize the
  existing import order.
- The parent POM probably needs the `substrate-api` module listed
  *before* `substrate-core` so Maven builds them in dependency order.
- Double-check that `substrate-api` has no test dependencies in its
  POM — it has no production code that requires testing at the module
  level beyond what's tested via `substrate-core`. (If `Snapshot.equals`
  or similar warrants a test, put it under `substrate-api/src/test/`.)
