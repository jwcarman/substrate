# Rename `AtomRecord` to `RawAtom`

## What to build

A mechanical rename of the SPI-level `AtomRecord` type (introduced in
spec 018) to `RawAtom`, for consistency with the existing
`RawJournalEntry` naming convention.

### Rationale

`substrate-core` already has `RawJournalEntry` as the SPI-level
representation of a journal entry — the raw `(byte[], String, ...)`
tuple that the core layer decodes via the `Codec` before returning to
user code. Atom has the same layering pattern but spec 018 named its
tuple `AtomRecord`, which:

1. **Doesn't rhyme with `RawJournalEntry`.** Two SPI layers, two
   different naming conventions, for no reason.
2. **Is slightly redundant.** The type is literally a Java `record`
   called `Record` — "AtomRecord" reads as "an atom record record."

`RawAtom` fixes both: matches the `Raw*` convention, and removes the
"record record" overlap.

## Files affected

All in `substrate-core`. No `substrate-api` changes — `AtomRecord` was
never exposed as a user-facing type.

### Renamed

- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/AtomRecord.java`
      → `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/RawAtom.java`
      with the class declaration updated to `public record RawAtom(byte[] value, String token) {}`.

### Modified (import + type reference updates)

- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/AtomSpi.java`
      — `Optional<AtomRecord> read(String key)` becomes
      `Optional<RawAtom> read(String key)`. Update the corresponding
      Javadoc reference if any.
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtom.java`
      — update the local variable in `get()` that holds the SPI return
      value (`AtomRecord record = ...` → `RawAtom record = ...`) and
      any type imports.
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/memory/atom/InMemoryAtomSpi.java`
      — update the return type on `read` and any internal construction
      of the record.
- [ ] Any test files that reference `AtomRecord` — at minimum
      `InMemoryAtomSpiTest`, possibly also `DefaultAtomTest` if it
      asserts on SPI-level return values.

### Not modified

- [ ] `substrate-api/.../atom/Atom.java` — no reference (user-facing
      surface uses `Snapshot<T>`, not `RawAtom`).
- [ ] `substrate-api/.../atom/Snapshot.java` — unrelated.
- [ ] `substrate-api/.../atom/AtomFactory.java` — no reference.
- [ ] Backend modules — there are no backend `AtomSpi` implementations
      yet (all future work), so nothing to update downstream.

## Acceptance criteria

- [ ] File `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/AtomRecord.java`
      no longer exists.
- [ ] File `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/RawAtom.java`
      exists and declares `public record RawAtom(byte[] value, String token) {}`
      with an Apache 2.0 license header.
- [ ] No file anywhere under `substrate-core` still imports or
      references the `AtomRecord` type. Verified by grep:
      `grep -r AtomRecord substrate-core/` returns no matches.
- [ ] `AtomSpi.read` returns `Optional<RawAtom>`.
- [ ] `DefaultAtom.get` still works and decodes the raw bytes via the
      codec into a `Snapshot<T>`.
- [ ] `InMemoryAtomSpi.read` still returns a populated `RawAtom` for
      live atoms and `Optional.empty()` for dead atoms.
- [ ] Existing atom tests still pass without any behavioral change.
- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`

## Implementation notes

- Use IDE-level rename refactoring if available — this is exactly the
  kind of change refactoring tools handle correctly.
- The rename is purely nominal. No behavior changes, no API surface
  changes at the `substrate-api` level, no backend module changes.
- The file rename itself may need to be done as "delete old, create
  new" rather than `git mv` if your editor insists on it. Either
  approach is fine as long as the old file doesn't end up in the
  final tree.
- Because this spec only touches `substrate-core` and nothing downstream
  has implemented `AtomSpi` yet, there is zero risk of breaking backend
  code. A future `PostgresAtomSpi` etc. will simply use the new name
  from day one.
