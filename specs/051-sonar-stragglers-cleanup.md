# Fix remaining Sonar code smells after the 041–050 sweep

## What to build

After specs 041 through 050 landed, 27 open Sonar issues remain.
Most are stragglers that slipped past earlier specs (S7467 in test
files, which spec 045 scoped to `src/main/java` only) or small
one-offs that fell out of other cleanups. Bundle them all into a
single focused pass.

## Rules and counts

From the live SonarCloud scan at commit `56893ad`:

| Rule | Count | Severity | Notes |
|---|---|---|---|
| `java:S7467` | 18 | MINOR | Unnamed `_` in catch blocks — mostly test files |
| `java:S1130` | 2 | MINOR | Unused `throws` declaration |
| `java:S6213` | 2 | MAJOR | Variable named `_` (restricted identifier) |
| `java:S5778` | 1 | MAJOR | assertThrows lambda with multiple throwing calls |
| `java:S4144` | 1 | MAJOR | Two methods with identical implementations |
| `java:S1068` | 1 | MAJOR | Unused private field |

**Out of scope for this spec:** `java:S1854` (one site in
`AbstractSingleSlotHandoff.java` — a consequence of the fix in
spec 042 and covered by spec 052 separately) and `java:S2326` on
`NextResult.java` (legitimate false positive — the type parameter
is structurally load-bearing on the sealed hierarchy; mark as
"won't fix" in SonarCloud UI).

## The sites

Drive the work from live Sonar queries — line numbers drift as
other cleanups land:

```
https://sonarcloud.io/api/issues/search?componentKeys=jwcarman_substrate&resolved=false&rules=java:S7467,java:S1130,java:S6213,java:S5778,java:S4144,java:S1068&ps=500
```

### S7467 — unnamed `_` in catch (18 sites)

Most of these are in `*IT.java` and `*Test.java` files across the
backend modules, plus one in `NatsMailboxSpi.java:73` (main code).
Same fix as spec 045: rename the caught exception variable to `_`
in catch blocks where the variable is unused. Follow spec 045's
rules about not touching catches that actually reference their
exception.

### S1130 — unused `throws` (2 sites)

- `substrate-core/.../mailbox/DefaultMailboxTest.java:170` —
  method declares `throws InterruptedException` but doesn't
  throw it. Spec 048's latch conversions likely removed the
  throwing call. Remove the declaration.
- `substrate-core/.../subscription/DefaultCallbackSubscriptionTest.java:201`
  — method declares `throws Exception` but doesn't throw. Remove.

### S6213 — variable named `_` (2 sites)

- `substrate-core/.../memory/atom/InMemoryAtomSpiTest.java:47, 132`
  — local variables literally named `_`. Java reserves `_` as the
  unnamed pattern; using it as a regular variable name is a
  warning. Rename to a descriptive name based on what the variable
  holds.

These are a spec 045 over-reach: spec 045 was scoped to catch
clauses, but someone renamed local variables too. Rename them
back to descriptive names (e.g., `ignored`, `unused`, or — better
— a name that describes what the variable actually contains).

### S5778 — assertThrows lambda straggler (1 site)

- `substrate-core/.../memory/mailbox/InMemoryMailboxSpiTest.java:91`
  — one of the 67 original S5778 sites that spec 044 missed. Lift
  setup calls out of the `assertThrows` lambda so only the single
  throwing statement remains inside. Same pattern as spec 044.

### S4144 — identical method bodies (1 site)

- `substrate-core/.../atom/DefaultAtomTest.java:157` has an
  implementation identical to `getThrowsOnDeadAtom` at line 89.

Two tests that evolved to the same body is usually a sign that
either (a) one of them is redundant and should be deleted, or
(b) they're testing different things that happen to share setup
and the shared setup should be extracted to a helper. Read both
methods, decide which case applies, fix accordingly. If in doubt,
delete the later method — the earlier one has seniority.

### S1068 — unused private field (1 site)

- `substrate-dynamodb/.../journal/DynamoDbJournalSpi.java:53` —
  an unused `ttl` private field. Probably a leftover from the
  Journal lifecycle refactor in spec 024. Remove the field and
  any constructor parameter that feeds it (if present). If the
  constructor parameter is part of a public API surface, audit
  callers before removing — but per the scan, Sonar believes the
  field is unused, so the parameter probably is too.

## Acceptance criteria

### Per-rule

- [ ] All 18 `java:S7467` sites are either fixed (variable renamed
      to `_`) or the catch genuinely references the variable and
      is left alone. Final count: 0 open S7467.
- [ ] Both `java:S1130` sites have their unused `throws`
      declarations removed.
- [ ] Both `java:S6213` sites have their `_` local variables
      renamed to descriptive names.
- [ ] The `java:S5778` straggler is refactored to contain one
      throwing call in the assertThrows lambda.
- [ ] The `java:S4144` duplicate is resolved by either deleting
      the redundant method or extracting a shared helper.
- [ ] The `java:S1068` unused field (and its constructor parameter,
      if applicable) is removed.

### Build

- [ ] `./mvnw verify` passes locally across all modules.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

### Sonar

- [ ] After the next scan, the following rules have zero open
      issues: `java:S7467`, `java:S1130`, `java:S6213`,
      `java:S5778`, `java:S4144`, `java:S1068`.
- [ ] `java:S1854` and `java:S2326` are untouched — those are
      handled outside this spec.

## Implementation notes

- Work rule-by-rule. Each rule has a consistent fix pattern, so
  batching by rule keeps diffs reviewable.
- For S7467 in the one main-code site (`NatsMailboxSpi.java:73`):
  verify the caught exception is truly unused before renaming.
  `NatsMailboxSpi` might log or wrap the exception — if so, do
  NOT rename, document the false positive instead.
- For S4144 on `DefaultAtomTest`: check both `getThrowsOnDeadAtom`
  (line 89) and whatever's at line 157. If one uses `read` and
  the other uses a different method, they test different behavior
  and should be kept but differentiated. If they really are
  identical, delete the later.
- For S1068 on `DynamoDbJournalSpi.ttl`: look for a constructor
  parameter feeding the field. The field and parameter should go
  away together. Check callers (auto-config, tests) to make sure
  nothing is passing `ttl` expecting it to matter.
