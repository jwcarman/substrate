# Fix Sonar S7467: use unnamed pattern variables in catch blocks

## What to build

SonarCloud flags **34 occurrences** of `java:S7467` (MINOR,
CODE_SMELL): "Replace 'e' with an unnamed pattern."

Java 22 introduced unnamed pattern variables (`_`) as a stable
feature (JEP 456), extending what was previewed in 21. The syntax
applies to `catch` blocks as well: when a caught exception is never
referenced inside the catch body, you can (and should) write
`catch (Exception _)` instead of `catch (Exception e)`. This
eliminates the unused-variable warning and makes the reader's
intent clear: the exception is deliberately being swallowed or
handled without reference to its details.

The project is already on Java 25, so the feature is available
everywhere.

Fix each flagged site by renaming the exception variable to `_`.
**Do not add or remove log statements**, do not change the exception
types caught, do not widen or narrow the catch. This is a pure
rename.

## Sample sites from the scan

- `substrate-core/.../core/journal/DefaultJournal.java:248, 265`
- `substrate-core/.../core/mailbox/DefaultMailbox.java:111`
- `substrate-core/.../core/subscription/FeederSupport.java:125`
- `substrate-core/.../core/subscription/BlockingBoundedHandoff.java:50`
- …29 more

All 34 sites are in `src/main/java`. Drive this from the live
Sonar query:

```
https://sonarcloud.io/api/issues/search?componentKeys=jwcarman_substrate&resolved=false&rules=java:S7467&ps=500
```

## Acceptance criteria

- [ ] Every `catch (SomeException name)` clause where `name` is
      unused in the catch body is rewritten to
      `catch (SomeException _)`.
- [ ] No `catch` clause that actually references its exception
      variable is changed — unnamed patterns are ONLY for
      deliberately-ignored exceptions.
- [ ] No log statements, error handling, or exception types are
      modified. This is a pure variable rename to `_`.
- [ ] `./mvnw verify` passes locally.
- [ ] `./mvnw spotless:check` passes — Spotless may need a
      `spotless:apply` if its formatter has opinions about
      the new token.
- [ ] SonarCloud `java:S7467` count for this project drops from
      34 to 0 on the next scan.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Be especially careful in `FeederSupport.runLoop`: the
  `catch (RuntimeException e)` block currently uses `e` for the
  `log.warn(..., e)` and `handoff.error(e)` calls, so that clause
  must NOT be renamed. The S7467 hit at `FeederSupport.java:125`
  is a different catch — likely the `catch (InterruptedException
  e)` block which re-interrupts but doesn't reference `e`.
  Verify each site before editing.
- Java only allows `_` for truly unused variables. If the variable
  is referenced even once inside the catch (even just for a
  `log.debug("...", e)` call), the compiler rejects `_`. Sonar's
  flag is reliable about this, but double-check anyway.
- The rename is mechanical but do NOT script it with a regex
  across the whole codebase — there are legitimate `catch (X e)`
  clauses that use `e`. Drive the edit from the Sonar issue list
  or module-by-module inspection.
- Spotless may reformat the touched files slightly. Run
  `./mvnw spotless:apply` at the end of each module's changes if
  `spotless:check` fails.
- Do not combine this cleanup with any other refactor. One rule,
  one spec.
