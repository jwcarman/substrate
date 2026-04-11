# Fix Sonar S5778: one exception-throwing call per assertThrows lambda

## What to build

SonarCloud flags **67 occurrences** of `java:S5778` (MAJOR, CODE_SMELL):
"Refactor the code of the lambda to have only one invocation
possibly throwing a runtime exception."

The rule's concern is ambiguity: if an `assertThrows(...)` /
`assertThatThrownBy(...)` / `assertThatExceptionOfType(...)` lambda
contains more than one statement that could throw the expected
exception, a passing test doesn't actually prove *which* statement
threw. A test titled `secondDeliveryThrowsMailboxFull` whose lambda
calls both `mailbox.deliver(...)` and `mailbox.deliver(...)` is
technically vulnerable to the first delivery throwing
`MailboxFullException` for an unrelated reason and the test still
passing.

Fix by lifting all setup calls OUT of the lambda, leaving only the
single statement that should throw inside. This makes the test's
intent unambiguous and satisfies the rule.

## The pattern

Before (flagged):

```java
assertThrows(
    MailboxFullException.class,
    () -> {
      mailbox.create(key, Duration.ofMinutes(5));
      mailbox.deliver(key, "first".getBytes(StandardCharsets.UTF_8));
      mailbox.deliver(key, "second".getBytes(StandardCharsets.UTF_8));
    });
```

After (compliant):

```java
mailbox.create(key, Duration.ofMinutes(5));
mailbox.deliver(key, "first".getBytes(StandardCharsets.UTF_8));

assertThrows(
    MailboxFullException.class,
    () -> mailbox.deliver(key, "second".getBytes(StandardCharsets.UTF_8)));
```

The same transformation applies to `assertThatThrownBy(() -> ...)`
and `assertThatExceptionOfType(...).isThrownBy(() -> ...)`.

## Scope — 67 sites across many test files

Sample from the Sonar scan:

- `substrate-redis/.../atom/RedisAtomIT.java:84`
- `substrate-postgresql/.../atom/PostgresAtomIT.java:77`
- `substrate-nats/.../atom/NatsAtomIT.java:67`
- `substrate-nats/.../atom/NatsAtomSpiTest.java:84, 102`
- …64 more

The full list changes as lines drift; drive this work from the live
Sonar query rather than a hardcoded list. The query below returns
every open instance:

```
https://sonarcloud.io/api/issues/search?componentKeys=jwcarman_substrate&resolved=false&rules=java:S5778&ps=500
```

or locally, grep for `assertThrows\|assertThatThrownBy\|isThrownBy`
across test sources and inspect each lambda.

## Acceptance criteria

- [ ] Every `assertThrows`, `assertThatThrownBy`, or
      `isThrownBy` lambda in the project contains exactly **one**
      statement that can throw the expected exception.
- [ ] Setup calls (create, deliver, connect, etc.) that previously
      lived inside a flagged lambda are lifted out and run before
      the assertion. The test's preconditions are established
      before the assertion is entered.
- [ ] The semantics of each test are preserved: the same exception
      is still being asserted for the same single action. Do not
      change the exception type or the action being tested.
- [ ] `./mvnw verify` passes locally; no existing test regresses.
- [ ] SonarCloud `java:S5778` count for this project drops from
      67 to 0 on the next scan.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- This is a mechanical refactor but inspect each site before
  editing. A small number of them may have legitimate reasons for
  multiple calls in the lambda (e.g., a helper method that wraps
  the throwing action). For those, extract the single throwing
  line and keep the helper call outside.
- Where a test has multiple `assertThrows` for *different* failure
  modes using the same setup, hoist the setup once before the
  first `assertThrows` rather than repeating it before each.
- For `assertThatThrownBy(() -> action()).isInstanceOf(X.class)`
  style chains, the same rule applies — `action()` must be the
  only throwing call in the lambda.
- If a lambda's non-throwing calls (getters, primitive ops) are
  currently inside the lambda, they're fine to leave. S5778 only
  flags calls that could plausibly throw.
- Be careful with try-with-resources patterns: if the lambda
  creates a closable resource, the `close()` can throw too. In
  those cases extract the resource creation outside the lambda
  in a try-with-resources that *contains* the assertion.
- Do not change test method names or docstrings.
- Do not combine multiple flagged tests into parameterized tests
  as part of this cleanup — that's a separate concern. Keep the
  diff narrow.

## Recommended workflow

Work module-by-module rather than file-by-file, because the same
SPI under test tends to have similar patterns. Suggested order
(smallest to largest per Sonar counts):

1. `substrate-cassandra`
2. `substrate-mongodb`
3. `substrate-dynamodb`
4. `substrate-hazelcast`
5. `substrate-nats`
6. `substrate-postgresql`
7. `substrate-redis`
8. `substrate-core`
9. `substrate-rabbitmq`

Run `./mvnw -pl <module> verify` after each module to catch
regressions early. Final full `./mvnw verify` before marking done.
