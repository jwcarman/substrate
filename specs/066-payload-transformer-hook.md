# Add PayloadTransformer hook between Codec and SPI

## What to build

A new extension point — `PayloadTransformer` — that sits between the
typed `Codec` and the backend `*Spi` and lets users rewrite the raw
`byte[]` on its way to and from storage. The primary use case is
encryption-at-rest; secondary uses include compression, integrity
signing, and custom framing. This spec only adds the hook itself and
wires in an identity default — concrete encryption implementations
are out of scope (see spec 067 for `substrate-crypto`).

### Layering

Substrate's data flow today:

```
user value ──[Codec]──> byte[] plaintext ────────────────> SPI storage
```

After this spec:

```
user value ──[Codec]──> byte[] plaintext ──[PayloadTransformer]──> byte[] ──> SPI storage
```

The transformer runs **after** serialization (the user's schema concerns
are already handled by `Codec`) and **before** storage (the backend is
schema-agnostic and just stores opaque bytes).

### Interface

```java
// substrate-api
package org.jwcarman.substrate;

/**
 * Transforms payload bytes on their way to and from the backend SPI. The primary use case is
 * encryption-at-rest — implementations apply encryption on {@link #encode} and decryption on
 * {@link #decode}. Other use cases include compression, integrity signing, or custom framing.
 *
 * <p>Must be deterministic in one direction: {@code decode(encode(x))} must equal {@code x} for
 * all inputs. The encode side MAY be non-deterministic (e.g. AES-GCM with a random nonce).
 *
 * <p>All methods are called on substrate primitive threads. Implementations MUST be thread-safe.
 *
 * <p>If an implementation rotates keys over time, make sure {@link #decode} can still read
 * payloads that were encoded with any historical key that may still exist in storage.
 */
public interface PayloadTransformer {

  /** Called before writing to the backend. Typical use: encrypt the bytes. */
  byte[] encode(byte[] plaintext);

  /** Called after reading from the backend. Typical use: decrypt the bytes. */
  byte[] decode(byte[] ciphertext);

  /** Pass-through transformer. The default when no concrete transformer is configured. */
  PayloadTransformer IDENTITY = new PayloadTransformer() {
    @Override public byte[] encode(byte[] plaintext)  { return plaintext; }
    @Override public byte[] decode(byte[] ciphertext) { return ciphertext; }
  };
}
```

### Integration with the primitives

**`DefaultAtom`** — add `PayloadTransformer` field. On write, apply
`encode` after `codec.encode`. On read (both `get()` and the feeder's
SPI read), apply `decode` before `codec.decode`. Staleness token is
still computed from the **plaintext** (so semantically-equal values
produce equal tokens regardless of the encryption nonce).

**`DefaultJournal`** — add `PayloadTransformer` field. On `append`,
apply `encode` after `codec.encode`. In `decode(RawJournalEntry)`, apply
`decode` before `codec.decode`.

**`DefaultMailbox`** — add `PayloadTransformer` field. On `deliver`,
apply `encode` after `codec.encode`. In the feeder step, apply `decode`
before `codec.decode`.

### Factories

`DefaultAtomFactory`, `DefaultJournalFactory`, `DefaultMailboxFactory`
each gain a `PayloadTransformer` constructor parameter and pass it
through to the `DefaultAtom` / `DefaultJournal` / `DefaultMailbox`
instances they create.

### Autoconfig

`SubstrateAutoConfiguration` provides an identity `PayloadTransformer`
bean with `@ConditionalOnMissingBean`:

```java
@Bean
@ConditionalOnMissingBean(PayloadTransformer.class)
public PayloadTransformer payloadTransformer() {
  return PayloadTransformer.IDENTITY;
}
```

The existing factory `@Bean` methods gain a `PayloadTransformer`
parameter and pass it to their respective factory constructors. If a
user registers their own `PayloadTransformer` bean, their bean wins;
otherwise `IDENTITY` is the default.

### What does NOT change

- The public `Atom<T>` / `Journal<T>` / `Mailbox<T>` APIs are unchanged.
  Users pass values in and get values out; they never see bytes.
- The `NotifierSpi` and the notifier routing layer are unchanged.
  Notifications carry only keys and event types, not value bytes.
- The `Snapshot<T>.token()` string is still computed from the plaintext
  encoding of the value, not from the ciphertext. A nondeterministic
  transformer (fresh nonce per encode) does NOT cause spurious
  "different token" triggers for the same semantic value.
- All backend `*Spi` implementations. Backends store whatever bytes the
  primitives hand them — they never learn about transformation.
- On-wire / on-disk format of previously-written data. Users who never
  register a transformer get identity behavior, identical to pre-spec
  storage.

### Token computation detail

In `DefaultAtom.token(byte[])`, the input is **still** the codec-encoded
plaintext. Do NOT pass the post-transform bytes. The token is a
user-level semantic "did the value change?" signal; transformation is
below that layer. Adding a javadoc note to the method to lock this in.

## Acceptance criteria

- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/PayloadTransformer.java` exists as a public interface with `encode(byte[])`, `decode(byte[])`, and a static `IDENTITY` constant that round-trips unchanged.
- [ ] `DefaultAtom`, `DefaultJournal`, `DefaultMailbox` take a `PayloadTransformer` via constructor, store it as a field, and apply `encode` on the write path and `decode` on every read path (`get()`, feeder step, `subscribe()` initial read, etc.).
- [ ] `DefaultAtomFactory`, `DefaultJournalFactory`, `DefaultMailboxFactory` take a `PayloadTransformer` parameter and thread it into the primitives they construct.
- [ ] `SubstrateAutoConfiguration` has a `@Bean @ConditionalOnMissingBean(PayloadTransformer.class)` that returns `PayloadTransformer.IDENTITY`. The factory `@Bean` methods take a `PayloadTransformer` parameter.
- [ ] Staleness tokens are still computed from codec-encoded plaintext, not from post-transform ciphertext. A non-deterministic transformer (e.g. hypothetical `RandomNoisePayloadTransformer` in a test) must produce identical tokens for identical input values.
- [ ] Tests: new `PayloadTransformerTest` in `substrate-api` covers the `IDENTITY` round-trip.
- [ ] Tests: new round-trip test in `substrate-core` that uses a simple deterministic transformer (e.g. "XOR every byte with 0xFF") and confirms that `atom.set(v); atom.get()` returns `v` unchanged, same for `journal.append(e) → subscribe().next()`, same for `mailbox.deliver(v) → subscribe().next()`.
- [ ] Tests: existing primitive and factory tests pass with `PayloadTransformer.IDENTITY` threaded through the new constructor parameter.
- [ ] `./mvnw verify` passes from the root. `./mvnw -P release javadoc:jar -DskipTests` passes (no new doclint errors).
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- When wiring into `DefaultJournal`, the feeder pushes decoded entries
  via a private helper. That helper currently does
  `codec.decode(raw.data())` — change it to
  `codec.decode(transformer.decode(raw.data()))`. The preloading path
  uses the same helper, so it gets the transform for free.
- When wiring into `DefaultMailbox`, both the feeder's one-shot SPI
  read AND any synchronous path need the transform. `DefaultMailbox`
  currently has a single feeder-step closure that does the read — put
  the transform there.
- `DefaultAtom.get()` currently does `codec.decode(raw.value())` —
  change to `codec.decode(transformer.decode(raw.value()))`. The feeder
  step does the same pattern for `subscribe(lastSeen)` reads.
- Don't try to "batch" the transform for things like
  `Journal.subscribeLast(count)` preload — each entry gets transformed
  independently. A future optimization could hand a `List<byte[]>` to
  the transformer for bulk operations, but that's YAGNI.
- The identity transformer should NOT be wrapped in any defensive
  array copy. `PayloadTransformer.IDENTITY.encode(bytes) == bytes` (by
  reference) is expected and correct — no per-call allocation cost for
  the default path.

## Out of scope

- Concrete encryption implementations. Those are in spec 067
  (`substrate-crypto`).
- Key management, rotation semantics, or any KMS integration.
- Per-primitive transformer configuration (e.g. "encrypt atoms but
  not journals"). One transformer applies to all primitives. Users
  who want selective transformation can write a transformer that
  routes internally based on some embedded marker — but substrate
  doesn't support that pattern directly.
- Compression or framing implementations. Those could be added later
  as concrete `PayloadTransformer` classes in a hypothetical
  `substrate-compression` module, but are not part of this spec.
- Changes to the staleness token algorithm — that's a separate
  concern already addressed at its current 128-bit truncated SHA-256.
