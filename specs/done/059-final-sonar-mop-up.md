# Final Sonar mop-up: 11 surgical fixes

## What to build

After specs 051–058, eleven Sonar issues remain. Most are
small misses or follow-on flags from earlier specs. Fix them all
in one focused pass to clear the dashboard.

## The 11 sites

### 1. BLOCKER — `java:S2699` missing assertion (1)

**`substrate-core/.../core/journal/AbstractJournalSpiTest.java:58`**

Spec 057 added a test method here as part of the
`AbstractJournalSpi` coverage push, but it has no assertion. Add
an `assertThatNoException` wrapper or a positive state assertion
matching the test method's name. Read the method to figure out
what it's actually trying to verify.

### 2. MAJOR — `java:S5778` assertThrows lambda stragglers (3)

**`substrate-nats/.../nats/mailbox/NatsMailboxSpiTest.java:247`**
**`substrate-nats/.../nats/mailbox/NatsMailboxSpiTest.java:261`**
**`substrate-core/.../core/memory/mailbox/InMemoryMailboxSpiTest.java:91`**

Two new ones from spec 058's gap-closing tests in
`NatsMailboxSpiTest`, plus the original `InMemoryMailboxSpiTest`
straggler that spec 051 missed.

Same fix as spec 044: lift any setup calls out of the
`assertThrows` / `assertThatThrownBy` lambda so only the single
throwing statement remains inside.

### 3. MAJOR — `java:S6213` variable named `_` (2)

**`substrate-core/.../core/memory/atom/InMemoryAtomSpiTest.java:47`**
**`substrate-core/.../core/memory/atom/InMemoryAtomSpiTest.java:132`**

Spec 051 was supposed to fix these but Ralph skipped them. Both
are local variables literally named `_`, which Java reserves as
the unnamed pattern.

Read each line and rename the variable to something descriptive
based on what it actually holds. Likely candidates: `ignored`,
`unused`, or — better — a name that says what the value would
be used for. Do NOT use any other reserved-word-adjacent names.

### 4. MINOR — `java:S5838` use `hasSameHashCodeAs` (2)

**`substrate-core/.../core/atom/RawAtomTest.java:56`**
**`substrate-hazelcast/.../hazelcast/atom/AtomEntryTest.java:56`**

Both files use the verbose form
`assertThat(a.hashCode()).isEqualTo(b.hashCode())`. AssertJ has
an idiomatic shortcut:

```java
// before
assertThat(a.hashCode()).isEqualTo(b.hashCode());

// after
assertThat(a).hasSameHashCodeAs(b);
```

Apply the rewrite at both sites. **This is a fix to spec 056's
implementation notes, which suggested the verbose form** — the
`hasSameHashCodeAs` shortcut is the right idiom.

### 5. MINOR — `java:S5853` chain multiple assertions (2)

**`substrate-core/.../core/atom/RawAtomTest.java:63`**
**`substrate-hazelcast/.../hazelcast/atom/AtomEntryTest.java:63`**

Both files have multiple `assertThat(rec.toString())` style
assertions on the same subject, which Sonar wants chained:

```java
// before
assertThat(rec.toString()).contains("foo");
assertThat(rec.toString()).contains("bar");

// after
assertThat(rec.toString()).contains("foo").contains("bar");
```

or use AssertJ's varargs form:

```java
assertThat(rec.toString()).contains("foo", "bar");
```

The varargs form is cleaner when both substrings are required.

### 6. MINOR — `java:S4030` unused collection (1)

**`substrate-cassandra/.../cassandra/journal/CassandraJournalSpiTest.java:249`**

A collection is built but never read. Either:
- The test is incomplete and should assert against the collection
  (likely — read the surrounding context to see what it was meant
  to verify)
- The collection-building code is dead and should be removed

Pick the right fix based on what the test method's name implies.
Don't blindly delete the collection if its construction has side
effects on the system under test.

## Acceptance criteria

- [ ] `AbstractJournalSpiTest:58` test method has at least one
      assertion that captures its existing intent.
- [ ] All 3 `S5778` assertThrows lambdas contain exactly one
      throwing statement; setup is lifted outside.
- [ ] Both `_` local variables in `InMemoryAtomSpiTest` are
      renamed to descriptive names.
- [ ] Both `hashCode` assertions use
      `assertThat(a).hasSameHashCodeAs(b)`.
- [ ] Both `toString` multi-assertion blocks are chained or use
      varargs `contains(...)`.
- [ ] The `CassandraJournalSpiTest:249` collection is either
      asserted against or its construction is removed.
- [ ] `./mvnw verify` passes locally.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.
- [ ] After the next Sonar scan, total open issue count drops to
      **zero** (modulo the `java:S2326` on `NextResult` which is
      being marked as won't-fix in the SonarCloud UI separately).

## Implementation notes

- Drive from the live Sonar query rather than line numbers — by
  the time this spec is worked, line numbers may have drifted
  from the values listed above:
  ```
  https://sonarcloud.io/api/issues/search?componentKeys=jwcarman_substrate&resolved=false&ps=500
  ```
- For the `S6213` rename: pick a name based on context. If the
  test creates a value just to verify a side effect occurred,
  `_` was wrong but so is renaming to `unused` — find a
  meaningful name like `entry` or `result`.
- For the `S2699` blocker: if the test method's body is doing
  something like `spi.create(...)` and was relying on "no exception
  thrown" as the implicit check, wrap the call in
  `assertThatNoException().isThrownBy(() -> ...)`. If a stronger
  assertion is obvious from the method name, prefer that.
- The `hasSameHashCodeAs` shortcut is in AssertJ's standard
  fluent API — no extra import beyond the existing
  `assertThat`.
