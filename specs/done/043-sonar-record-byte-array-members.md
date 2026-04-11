# Fix Sonar S6218: override equals/hashCode/toString on records with byte[] fields

## What to build

SonarCloud flags **3 record types** with `byte[]` components for
`java:S6218` (MAJOR, BUG): "Override equals, hashCode and toString
to consider array's content in the method."

This is a known Java records limitation: the default `equals` and
`hashCode` generated for a record use `Object.equals`/`Object.hashCode`
semantics for its components, which for `byte[]` means **reference
equality**, not content equality. Two records with identical byte
content but different array identities will be unequal, and their
`toString()` will print something like `[B@1f32e575` instead of the
bytes.

For substrate's primitive types this matters because record
equality is load-bearing in some places — e.g., tests that compare
SPI read results against expected values, or `Set<RawAtom>` usage
anywhere. Fix by overriding all three methods to use `Arrays.equals`
/ `Arrays.hashCode` / `Arrays.toString` for the `byte[]` components
while delegating to normal equality for the other components.

## The 3 records

1. **`substrate-core/.../core/atom/RawAtom.java`** (line 27)
   — public record used across all AtomSpi implementations. Has
   `byte[] value` and `String token`. Override to use
   `Arrays.equals`/`Arrays.hashCode` for `value` and normal equality
   for `token`.

2. **`substrate-hazelcast/.../hazelcast/atom/AtomEntry.java`** (line 20)
   — Hazelcast-backend internal record for storing atoms in the
   distributed map. Check its components and apply the same
   pattern.

3. **`substrate-core/.../core/memory/atom/InMemoryAtomSpi.java`** (line 29)
   — this line is inside `InMemoryAtomSpi`, so the record is
   nested or a file-private helper (likely a `Stored` or `Entry`
   record holding the atom value + TTL metadata). Find it, check
   its components, apply the same pattern.

## Acceptance criteria

- [ ] `RawAtom` overrides `equals(Object)`, `hashCode()`, and
      `toString()` to use `Arrays.equals(byte[], byte[])`,
      `Arrays.hashCode(byte[])`, and `Arrays.toString(byte[])` for
      its `byte[] value` component.
- [ ] The Hazelcast `AtomEntry` record receives the same treatment
      for its `byte[]` component(s).
- [ ] The InMemoryAtomSpi internal record at line 29 receives the
      same treatment.
- [ ] All other components (strings, Instants, tokens, TTLs, etc.)
      continue to use normal Java equality — do not hand-roll
      equality for non-array fields.
- [ ] Equality semantics change as intended: two records with
      byte-identical `value` arrays are now `equals`, and two
      records with different content are not.
- [ ] `./mvnw verify` passes. If any existing test was implicitly
      relying on **reference** equality for a `byte[]` component,
      that test's behavior is reviewed and either (a) the test is
      correct under content equality and continues to pass, or
      (b) the test was testing the wrong thing and needs a
      targeted update. Do NOT revert the record fix to make a
      test pass — fix the test.
- [ ] `toString()` on each record now prints a useful representation
      of the byte array (either hex, base64, or `Arrays.toString`
      — any readable form). This is particularly important for
      test failure messages where the default `[B@hex` is unhelpful.
- [ ] SonarCloud `java:S6218` count drops by 3 on the next scan.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- A safe template for a record override is:
  ```java
  public record RawAtom(byte[] value, String token) {
    @Override
    public boolean equals(Object other) {
      return other instanceof RawAtom(byte[] v, String t)
          && Arrays.equals(value, v)
          && Objects.equals(token, t);
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(value), token);
    }

    @Override
    public String toString() {
      return "RawAtom[value=" + Arrays.toString(value) + ", token=" + token + "]";
    }
  }
  ```
  The record deconstruction pattern in `equals` uses Java 21+
  features that the project already relies on.
- For `toString()`, `Arrays.toString(bytes)` is the standard choice;
  it prints each byte as a decimal integer. If any of the flagged
  records hold values that are typically string data, consider a
  format like
  `new String(value, StandardCharsets.UTF_8)` — but only if you
  know the values are always UTF-8. When in doubt, use
  `Arrays.toString`.
- This spec touches record definitions only; no SPI method signatures
  change. Do not add new accessors or modify the component list.
- If the Sonar line numbers have drifted by the time this spec is
  worked, search for `record.*byte\[\]` in the listed modules and
  match by class name rather than line number.
