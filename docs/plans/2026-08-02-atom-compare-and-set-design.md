# Atom compare-and-set

**Date:** 2026-08-02
**Target version:** 0.8.0 (pre-1.0; breaking changes permitted)

## Problem

`Atom.set(T, Duration)` is unconditional: the last writer wins and silently
discards any concurrent update. There is no way for a caller to express "write
this only if nobody has changed the value since I read it," so read-modify-write
workflows on an Atom are unsafe under contention.

`Snapshot<T>` already carries an opaque `token`, and `AtomSpi`'s class javadoc
already claims implementations provide a "conditional set (returning success or
failure for compare-and-swap semantics)." The token is threaded end-to-end
through every backend. The machinery is present; only the conditional write is
missing.

## Goals

- Add a compare-and-set write to the public `Atom` API, keyed on the existing
  `Snapshot` token.
- Make the token a sound basis for CAS (today it is not — see below).
- Implement it exactly and atomically on every backend that can, and document
  the single backend that cannot.
- Fix the confirmed TTL bug in `HazelcastAtomSpi.set` (see §4a), which the
  atomic-write work resolves as a side effect.

Existing storage formats and implementation shapes are **not** treated as
constraints. Where the current structure blocks a correct implementation
(Redis) or is already incorrect (Hazelcast), it gets replaced.

## Non-goals

- Removing `set(T, Duration)`. The single-writer case (a leader publishing
  state, a heartbeat, a cache refresh) has no contention and should not pay a
  read round-trip plus a retry loop to write. `Atom` follows
  `java.util.concurrent.atomic.AtomicReference`, which offers both.
- A built-in retry helper (`update(UnaryOperator<T>, Duration)`) or a
  `compareAndExchange` variant. Deferred until there is demand; `compareAndSet`
  is the primitive both would be built on.
- A shared `AtomSpi` contract/TCK test base class. Backend ITs are hand-written
  today (~240 LOC each across nine modules); extracting a contract suite is a
  worthwhile refactor but is out of scope here, to keep this change focused.

## Design

### 1. Public API (`substrate-api`)

One new method on `Atom<T>`, alongside the unchanged `set`:

```java
/**
 * Atomically sets the value only if this atom's current token matches the
 * token carried by {@code expected}. On success the TTL is reset and
 * subscribers are notified; on failure nothing is written.
 *
 * @param expected the snapshot this write is conditioned on
 * @param data     the new value to store
 * @param ttl      the new time-to-live for this atom
 * @return {@code true} if the write was committed; {@code false} if another
 *     writer changed the atom first
 * @throws AtomExpiredException if the atom's lease has elapsed or it has
 *     been deleted
 * @throws NullPointerException if {@code expected} is null
 * @throws IllegalArgumentException if {@code ttl} exceeds the configured maximum
 */
boolean compareAndSet(Snapshot<T> expected, T data, Duration ttl);
```

Three outcomes, deliberately kept distinct:

| Condition | Result |
| --- | --- |
| Token matched; write committed | `true` |
| Token no longer matches | `false` — **retryable** |
| Atom expired or deleted | `AtomExpiredException` — **terminal** |

Collapsing the last two into `false` would make the obvious
`while (!atom.compareAndSet(...)) { ... }` loop spin forever against a dead
atom. This distinction is the reason the SPI returns an enum rather than a
boolean.

Intended usage:

```java
Snapshot<Session> cur = atom.get();
Session next = cur.value().withCount(cur.value().count() + 1);
if (!atom.compareAndSet(cur, next, TTL)) {
  // lost the race — re-read and retry
}
```

### 2. Token becomes a per-write nonce (`substrate-core`)

`DefaultAtom.token(byte[])` currently returns the first 128 bits of
SHA-256 over the encoded value. That makes the token a *value identity*, not a
*write identity*, which has two consequences that make it a poor CAS basis:

- **ABA.** A value that goes A → B → A returns the token to its original, so a
  CAS conditioned on the original token succeeds even though two writes
  intervened.
- **Identical writes are invisible.** Re-setting the current value produces the
  same token, so a CAS on it "succeeds" without representing a distinct write —
  and subscribers are never notified.

Replace it with a random per-write nonce:

```java
private static final int TOKEN_BYTES = 16;

static String nextToken() {
  byte[] bytes = new byte[TOKEN_BYTES];
  ThreadLocalRandom.current().nextBytes(bytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
}
```

128 random bits, Base64URL-encoded to the same 22 characters the hash produced,
so no storage or wire-format assumptions change. `ThreadLocalRandom` rather than
`SecureRandom`: the token is a staleness marker, not a security credential, and
`SecureRandom` contends under concurrent writers. `MessageDigest`/`SHA-256` drop
out of `DefaultAtom` entirely.

**Backends require no change for this.** They persist the token as an opaque
string and never inspect it. Three call sites in core cover it:
`DefaultAtom.set` and `DefaultAtomFactory.create` (×2, both of which simplify —
they no longer need the encoded bytes to derive a token).

**Breaking behavior change:** `set()` called with a value equal to the current
one now moves the token and therefore fires a subscriber notification, where
today it is silently invisible. This is the more correct behavior — a write
that renews the lease is a real event watchers should see — but it is a
behavior change and gets a CHANGELOG breaking-changes entry.

### 3. SPI (`substrate-core`)

A new enum and one new method on `AtomSpi`. The method is abstract, not a
defaulted no-op, so every backend is forced to implement it:

```java
/** Outcome of a conditional {@link AtomSpi#compareAndSet} write. */
public enum CasResult {
  /** The expected token matched and the new value was written. */
  COMMITTED,
  /** A live atom exists but its token differs from the expected token. */
  TOKEN_MISMATCH,
  /** No live atom exists at the key (expired or deleted). */
  ABSENT
}
```

```java
/**
 * Writes a new value and token only if the atom's current token equals
 * {@code expectedToken}, resetting its TTL on success.
 */
CasResult compareAndSet(String key, String expectedToken,
                        byte[] value, String newToken, Duration ttl);
```

`DefaultAtom` maps the enum onto the public contract:

```java
@Override
public boolean compareAndSet(Snapshot<T> expected, T data, Duration ttl) {
  Objects.requireNonNull(expected, "expected");
  ensureExists();
  validateTtl(ttl);
  byte[] bytes = codec.encode(data);
  CasResult result = context.spi().compareAndSet(
      key, expected.token(), context.transformer().encode(bytes), nextToken(), ttl);
  return switch (result) {
    case COMMITTED -> {
      context.notifier().notifyAtomChanged(key);
      yield true;
    }
    case TOKEN_MISMATCH -> false;
    case ABSENT -> throw new AtomExpiredException(key);
  };
}
```

Notifications fire only on `COMMITTED`.

### 4. Backend implementations

Eight of nine backends can produce all three outcomes from a single atomic
operation.

| Backend | Mechanism | Exact |
| --- | --- | --- |
| in-memory | `ConcurrentHashMap.compute`, inspecting the existing entry | yes |
| postgresql | One CTE statement: a `SELECT` of the current token beside the guarded `UPDATE ... AND token = ?`, both on the same snapshot | yes |
| cassandra | The already-prepared `updateIfToken` LWT (`UPDATE ... USING TTL ? SET value=?, token=? WHERE key=? IF token=?`); on `[applied]=false` the result row carries the current token, and a null token column means absent | yes |
| dynamodb | Extend the existing `conditionExpression` with `AND #tok = :expected` and set `ReturnValuesOnConditionCheckFailure=ALL_OLD`; the thrown `ConditionalCheckFailedException` carries the old item — no item (or a lapsed `ttl` attribute, since DynamoDB reaps lazily) means absent | yes |
| mongodb | `findAndModify` with an aggregation-pipeline update whose `$set` stages are wrapped in `$cond` on the token, filtered on key + not-expired, returning the BEFORE document: null → absent, token mismatch → unchanged, match → committed | yes |
| hazelcast | An `EntryProcessor` — see §4a | yes |
| redis | A Lua `EVAL`, which Redis runs atomically, returning a status code | yes¹ |
| etcd | A txn comparing `ModRevision` against the revision observed by a preceding read, with `Else(Get(key))` to classify a failed compare | yes |
| nats | KV `update(key, value, expectedRevision)` after a read that maps token → revision | **no²** |

¹ **Redis changes storage format.** `AtomPayload` currently encodes
`[4-byte token length][token][value]` and Base64-encodes the whole blob into a
single string value, which Lua cannot parse (no native Base64 decode).

The entry becomes a **Redis hash** with separate `token` and `value` fields, so
the script can `HGET key token`, compare, then `HSET` + `EXPIRE`. This is also
the more idiomatic modeling of a two-field record, and it drops the hand-rolled
length-prefixed framing entirely. `create`, `read`, `set`, and `touch` in
`RedisAtomSpi` move to hash commands (`HSETNX`/`HSET`/`HGETALL`), and
`AtomPayload` is deleted.

Atomicity is preserved throughout: `create` becomes a small Lua script (or
`HSETNX` + `EXPIRE` inside one script) so the entry can never exist without a
TTL. The on-the-wire format change is not a concern — Atom entries are
TTL-leased ephemeral state, not durable data.

² **NATS is the one exception.** NATS KV has no transaction and no
conditional-read-on-failure. The *write* is still exact: `update` with an
expected revision is a genuine atomic CAS, and the revision guard closes the
race, so no lost update is possible. Only the *classification of a failure* is
approximate — it requires a follow-up `get`, and if the atom is deleted in the
window between the failed update and that read, the failure is reported as
`ABSENT` (throwing `AtomExpiredException`) when it was really
`TOKEN_MISMATCH`. Both outcomes mean the caller lost, and `ABSENT` is a truthful
statement about the atom at the moment it is read, so the misclassification is
benign. It is documented in `NatsAtomSpi`.

### 4a. Hazelcast: fixing a confirmed TTL bug

`HazelcastAtomSpi.set` currently writes in two steps:

```java
AtomEntry previous = map.replace(key, new AtomEntry(value, token));
if (previous == null) return false;
map.setTtl(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
return true;
```

`IMap.replace(key, value)` discards the entry's per-entry TTL. Verified
empirically against the Hazelcast version this project builds against, by
reading `map.getEntryView(key).getExpirationTime()` at each step:

```
after putIfAbsent(ttl=60s) : 1785674609000
after replace(key, value)  : 9223372036854775807   (Long.MAX_VALUE)
after setTtl(60s)          : 1785674609000
```

Between `replace` and `setTtl` the atom is **immortal**. If the JVM exits, the
member fails over, or `setTtl` fails in that window, the atom never expires —
and `HazelcastAtomSpi` inherits the no-op `sweep()` from `AbstractAtomSpi`, so
nothing reclaims it. The lease guarantee that Atom is built on is silently void
for that key, permanently. Secondarily, `setTtl`'s boolean return is discarded,
so `set` reports success even when the TTL was never applied.

**Fix:** route both `set` and `compareAndSet` through an `EntryProcessor`. It
runs on the partition thread with the key locked, and `ExtendedMapEntry
.setValue(value, ttl, unit)` applies the value and the TTL as one operation.
This yields a genuine single-step atomic write, returns the three-way
`CasResult` directly, and eliminates the immortality window.

**Deployment note (document in the module README and `HazelcastAtomSpi`
javadoc):** an `EntryProcessor` class must be present on every cluster
*member's* classpath. This is free for embedded Hazelcast. Deployments that
point `substrate-hazelcast` at a remote cluster through a Hazelcast client must
either put the substrate jar on the members or enable user-code deployment.
This is a new requirement for the Hazelcast backend and belongs in the
CHANGELOG.

### 5. Tests

Following the repo's existing per-backend IT convention rather than introducing
a shared contract suite:

- `DefaultAtomTest` — the enum-to-contract mapping: `COMMITTED` returns true and
  notifies, `TOKEN_MISMATCH` returns false and does not notify, `ABSENT` throws
  `AtomExpiredException`; null `expected` throws; TTL validation still applies.
- Token change — `set()` with an identical value now yields a different token
  and delivers to subscribers. Assert the new token's shape (22 chars,
  Base64URL) and that repeated calls differ.
- Each of the nine backend SPI ITs/unit tests gains the same four cases: CAS
  with a matching token commits and resets TTL; CAS with a stale token returns
  `TOKEN_MISMATCH` and leaves the stored value untouched; CAS against a deleted
  key returns `ABSENT`; CAS against an expired key returns `ABSENT`.
- One concurrency test (in-memory is sufficient): N threads incrementing through
  a CAS retry loop land on exactly N.
- **Hazelcast TTL regression test** (`HazelcastAtomIT`): after `create` with a
  TTL followed by `set` with a TTL, assert
  `map.getEntryView(key).getExpirationTime()` is finite and close to the
  expected deadline — i.e. never `Long.MAX_VALUE` at any observable point. Same
  assertion after `compareAndSet`. This is the test that would have caught the
  bug in §4a.

### 6. Documentation

- `Atom` javadoc: a compare-and-set usage example beside the existing blocking
  and callback examples.
- `Snapshot` javadoc: the token is now a per-write marker, and it is the value
  passed to `compareAndSet`.
- `AtomSpi` javadoc: its existing "conditional set ... for compare-and-swap
  semantics" claim becomes accurate; document the `CasResult` contract and the
  requirement that implementations be atomic.
- `README.md`: the Atom section (~line 86) gains a CAS example; the exception
  table (~line 413) notes `AtomExpiredException` is also thrown by
  `compareAndSet`.
- `substrate-hazelcast` README/javadoc: the `EntryProcessor` member-classpath
  requirement from §4a.
- `CHANGELOG.md` under `[Unreleased]`:
  - `### Added` — `Atom.compareAndSet`; `AtomSpi.compareAndSet` and `CasResult`.
  - `### Fixed` — `substrate-hazelcast`: `set()` left the atom with no
    expiration between its `replace` and `setTtl` calls, so an interruption in
    that window produced an atom that never expired and was never swept.
  - `### Breaking changes` — the Snapshot token is now a per-write nonce rather
    than a content hash; `set()` with an unchanged value now notifies
    subscribers; `AtomSpi` gained an abstract method, so third-party
    implementations must add it; `substrate-redis` changes its Atom storage
    format from a single Base64 string to a hash (ephemeral leased state, so no
    migration path is provided); `substrate-hazelcast` now requires its classes
    on cluster-member classpaths in client-server topologies.
