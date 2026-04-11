# Test `RawAtom` and Hazelcast `AtomEntry` equality overrides

## What to build

Two record classes sit at 8.3% coverage because their custom
`equals`/`hashCode`/`toString` methods (added in spec 043 for the
`java:S6218` byte[] bug fix) aren't directly exercised by any
test:

| File | Coverage | Uncovered lines |
|---|---|---|
| `substrate-core/.../core/atom/RawAtom.java` | 8.3% | 5 |
| `substrate-hazelcast/.../atom/AtomEntry.java` | 8.3% | 5 |

The overrides exist to ensure `byte[]` components compare by
content, not reference. Right now they're load-bearing (without
them, tests comparing SPI read results against expected values
could silently break), but no test directly exercises the equality
contract.

Add dedicated test classes covering:

1. **Content equality** — two records with the same byte[]
   content and same non-array fields are `equals`, even if the
   arrays are different identity.
2. **Inequality on byte[] difference** — two records with
   different byte[] content are NOT `equals`, even if other
   fields match.
3. **Inequality on other field difference** — two records with
   identical byte[] but different token/other fields are not
   `equals`.
4. **`hashCode` consistency** — two `equals` records have the
   same `hashCode`.
5. **`toString` contains readable byte[] representation** — not
   just `[B@hex`. The exact format isn't load-bearing (could be
   `Arrays.toString` or a custom format) but it must include
   enough information that a test failure message is useful.

## Acceptance criteria

### `RawAtomTest`

- [ ] `substrate-core/src/test/java/.../core/atom/RawAtomTest.java`
      exists.
- [ ] Test: `equalsReturnsTrueForIdenticalContent` — two `RawAtom`
      instances with the same byte[] content (but distinct
      arrays) and the same token are `equals`.
- [ ] Test: `equalsReturnsFalseForDifferentValue` — two with
      different byte[] content are not `equals`.
- [ ] Test: `equalsReturnsFalseForDifferentToken` — two with
      identical byte[] but different tokens are not `equals`.
- [ ] Test: `hashCodeMatchesForEqualRecords` — two `equals`
      records have the same `hashCode`.
- [ ] Test: `toStringIncludesValueAndToken` — `toString()` output
      contains the token verbatim and some representation of the
      byte[] that isn't `[B@`.
- [ ] Coverage of `RawAtom.java` > 90% on the next Sonar scan.

### `AtomEntryTest` (Hazelcast)

- [ ] `substrate-hazelcast/src/test/java/.../atom/AtomEntryTest.java`
      exists.
- [ ] Same five test patterns, adapted to `AtomEntry`'s component
      list (read the record definition to get the exact fields).
- [ ] Coverage of `AtomEntry.java` > 90% on the next Sonar scan.

### Build

- [ ] `./mvnw -pl substrate-core verify` passes.
- [ ] `./mvnw -pl substrate-hazelcast verify` passes.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Use distinct `byte[]` arrays with identical content to verify
  content equality:
  ```java
  byte[] a = new byte[] {1, 2, 3};
  byte[] b = new byte[] {1, 2, 3};
  assertThat(a).isNotSameAs(b);  // sanity check
  assertThat(new RawAtom(a, "t")).isEqualTo(new RawAtom(b, "t"));
  ```
- Do NOT use `assertThat(x).isEqualTo(y)` without first
  constructing `x` and `y` with distinct arrays — otherwise
  you're testing reference equality, which is the default
  behavior and would pass even if the override were broken.
- Include a `hashCode` assertion explicitly:
  `assertThat(recA.hashCode()).isEqualTo(recB.hashCode())`.
  AssertJ's `isEqualTo` does NOT check hashCode.
- For `toString`, use `assertThat(rec.toString()).contains(...)`
  rather than exact-match assertions. The exact format shouldn't
  be load-bearing.
- If `AtomEntry` has additional non-byte[] components beyond
  what `RawAtom` has, test those field's inequality paths too.
