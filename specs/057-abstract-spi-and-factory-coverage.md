# Cover abstract SPI bases and factory gaps

## What to build

Several `Abstract*Spi` and `Default*Factory` classes in
`substrate-core` sit below 85% coverage. The gaps are small (1–4
uncovered lines each) and are usually one or two uncovered methods
or branches in otherwise well-tested classes.

| File | Coverage | Uncovered |
|---|---|---|
| `substrate-core/.../core/atom/AbstractAtomSpi.java` | 66.7% | 2 |
| `substrate-core/.../core/journal/AbstractJournalSpi.java` | 80.0% | 2 |
| `substrate-core/.../core/mailbox/AbstractMailboxSpi.java` | 83.3% | 1 |
| `substrate-core/.../core/mailbox/DefaultMailboxFactory.java` | 75.0% | 4 |
| `substrate-core/.../core/journal/DefaultJournalFactory.java` | 90.0% | 3 |
| `substrate-core/.../core/subscription/DefaultCallbackSubscriberBuilder.java` | 84.6% | 2 |
| `substrate-sns/.../notifier/SnsNotifierAutoConfiguration.java` | 80.0% | 2 |

Each of these is a one-test or two-test addition. Read the JaCoCo
report to find the specific red lines, then add focused tests
that exercise them.

## Typical gap patterns

**Abstract SPI key-prefix handling.** The `AbstractAtomSpi` /
`AbstractJournalSpi` / `AbstractMailboxSpi` classes usually
implement a `xxxKey(String suffix)` helper that prepends a
configured prefix. Tests exercise the main operation paths but
may not exercise a "null prefix" or "empty suffix" edge case.

**Factory TTL validation.** `DefaultMailboxFactory` /
`DefaultJournalFactory` typically validate that the TTL argument
is positive and non-null. The happy path is well-tested; the
`IllegalArgumentException` branches may not be.

**Builder accumulation.** `DefaultCallbackSubscriberBuilder`'s
`errorHandler`, `expirationHandler`, etc. setters each return
`this`. The "no handler set" default paths (where the builder's
getters return null or a no-op) may not be exercised.

**SNS conditional wiring.** `SnsNotifierAutoConfiguration` likely
has a `@ConditionalOnProperty` or `@ConditionalOnBean` branch
that the test doesn't exercise.

## Acceptance criteria

For each of the 7 files listed above:

- [ ] Coverage rises to > 90% on the next Sonar scan.
- [ ] The test additions are small and focused — a single
      `@Test` method per uncovered line/branch is fine if that's
      what it takes.
- [ ] New tests live in the same module's existing test source
      tree and extend existing test classes where reasonable.
      A new test class is only necessary if none exists for the
      production class (unlikely for these).
- [ ] `./mvnw verify` passes.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Start by running
  `./mvnw -pl substrate-core verify`
  and opening the JaCoCo HTML report at
  `substrate-core/target/site/jacoco/index.html`. Drill into
  each listed class to see the red (uncovered) lines.
- The "uncovered lines" count from Sonar is an approximation.
  JaCoCo may report slightly different numbers — trust JaCoCo
  for what to cover, use Sonar to verify the final state.
- Do NOT refactor production code to make it easier to test.
  If a class is awkward to cover, note it but work around it
  with the tests available. Refactoring-for-testability is a
  different concern.
- For `SnsNotifierAutoConfiguration`, mock the SNS/SQS clients
  via a `@TestConfiguration` — do not start LocalStack just to
  cover auto-config branches.
- Some "uncovered" lines in abstract classes may be abstract
  method declarations that JaCoCo counts as uncovered. Those
  genuinely cannot be directly tested. If this turns out to be
  the case, document it in the commit message and accept the
  residual gap.
