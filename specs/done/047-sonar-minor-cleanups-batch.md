# Fix minor Sonar code smells: unused locals, empty blocks, and friends

## What to build

After specs 041–046 clear the blockers, criticals, bugs, and the
two high-volume rules, roughly **20 minor code smells** remain.
This spec bundles them into a single cleanup pass. Each is small,
mechanical, and independent of the others.

## Rules in scope

From the current SonarCloud scan at commit `d28f69c`:

| Rule | Count | Description |
|---|---|---|
| `java:S1481` | 8 | Unused local variables — remove or use `_` |
| `java:S108` | 6 | Nested blocks of code should not be left empty |
| `java:S1192` | 2 | String literal duplicated (beyond the 2 criticals in spec 042) |
| `java:S6213` | 2 | Use pattern matching for `instanceof` |
| `java:S2326` | 1 | Unused generic type parameter |
| `java:S2737` | 1 | `catch` clauses should do more than rethrow |
| `java:S4144` | 1 | Two methods should not have the same implementation |
| `java:S1068` | 1 | Unused private field |

Total: ~22 issues. (Exact numbers may shift as other specs land.)

## Per-rule guidance

### S1481 — unused local variables (8)
- Remove the variable if the expression has no side effect.
- If the expression has a side effect (e.g., consuming a stream,
  triggering a computation), replace the variable name with `_`
  (Java 22+ unnamed pattern).
- If it's a caught exception in a catch block, that's S7467
  territory — leave for spec 045.

### S108 — empty nested blocks (6)
- Most hits are empty `catch` blocks. Either add a comment
  explaining why the exception is deliberately ignored, or
  rewrite to log the exception. Empty catches almost always
  indicate a silent failure.
- If the empty block is a placeholder `{}` for a
  functional-interface impl (e.g., `onNext = value -> {}`),
  add a short comment `/* no-op */` so the intent is explicit.

### S1192 — duplicated string literals (2)
- Extract to `private static final String` constants, same
  approach as spec 042's CRITICAL fixes.

### S6213 — use pattern matching for instanceof (2)
- Java 16+ feature, already used throughout the codebase. Rewrite
  `if (x instanceof Foo) { Foo f = (Foo) x; … }` to
  `if (x instanceof Foo f) { … }`. Mechanical.

### S2326 — unused generic type parameter (1)
- A class or method declares `<T>` but doesn't use it. Either
  use it or remove it. Removing is usually safe if the method
  signature doesn't change.

### S2737 — catch clause only rethrows (1)
- Either add meaningful handling (log, wrap, enrich) or delete
  the try/catch entirely. Per substrate's established pattern
  (no wrapping for wrapping's sake), deleting the try/catch is
  usually the right call.

### S4144 — two methods with same implementation (1)
- Refactor: make one delegate to the other, or extract a shared
  private helper. Do not inline both to a single caller unless
  the methods are only called once each.

### S1068 — unused private field (1)
- Remove the field. If a test depends on the field being present
  (unlikely for private fields), investigate before deleting.

## Acceptance criteria

- [ ] Every flagged site is fixed per the per-rule guidance
      above. No site is silenced via `@SuppressWarnings`.
- [ ] `./mvnw verify` passes locally; no test regresses.
- [ ] `./mvnw spotless:check` passes.
- [ ] SonarCloud issue counts on the next scan for rules
      S1481, S108, S1192 (non-critical), S6213, S2326, S2737,
      S4144, S1068 all drop to zero.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Work rule-by-rule rather than site-by-site — the fixes within
  a single rule are all shaped the same, so batching keeps the
  cognitive cost low and the diff reviewable.
- Drive the work from live Sonar queries so line numbers are
  accurate:
  ```
  https://sonarcloud.io/api/issues/search?componentKeys=jwcarman_substrate&resolved=false&rules=java:S1481,java:S108,java:S1192,java:S6213,java:S2326,java:S2737,java:S4144,java:S1068&ps=500
  ```
- Don't touch sites flagged by rules NOT in this spec. Stay
  narrow — if Sonar raises other minor rules after landing this,
  that's a new spec.
- If a fix in this spec happens to also resolve an issue flagged
  by another rule (e.g., deleting a try/catch resolves both
  S2737 and an S7467 unnamed-catch hit), that's fine — note it
  in the commit message.
- This is the last Sonar cleanup spec in the current queue. After
  it lands, the project should be at or near 0 open Sonar issues,
  making future regressions immediately visible in scan results.
