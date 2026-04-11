# Fix Sonar blockers: add missing test assertions

## What to build

SonarCloud flags **7 test methods** as BLOCKER violations of `java:S2699`
("Tests should include assertions"). Each of these tests exercises
behavior that should not throw — typically an idempotency check like
"deleting a nonexistent key is a no-op" or "second delete succeeds" —
but expresses that expectation by simply *not* catching any exception
rather than by explicitly asserting it. That's what Sonar is
complaining about: the test has no `assertThat`, `assertEquals`, or
equivalent, so it technically passes even if the body is empty or if
behavior silently regresses to "no exception, no effect."

Fix each flagged method by making the "does not throw" intent
explicit. The preferred form is to wrap the exercised calls in
`assertThatNoException().isThrownBy(() -> { ... })`, which AssertJ
exposes and which Sonar recognizes as a valid assertion. Where a
stronger positive assertion is obvious (e.g., "after delete, read
returns `Optional.empty()`"), prefer that — but do not invent new
behavioral guarantees; the goal is to capture the test's existing
intent, not to extend coverage.

## The 7 sites

All of these are flagged on the reported line as of the Sonar scan
at commit `d28f69c`. Line numbers may have drifted slightly by the
time this spec is worked; the method on or near each line is what
to fix.

| File | Line | Hint |
|---|---|---|
| `substrate-redis/src/test/.../atom/RedisAtomIT.java` | 153 | `deleteIs*` style test |
| `substrate-redis/src/test/.../mailbox/RedisMailboxTest.java` | 51 | look for a `@Test` without any assertion |
| `substrate-mongodb/src/test/.../atom/MongoDbAtomSpiIT.java` | 134 | likely the `delete` idempotency test |
| `substrate-hazelcast/src/test/.../atom/HazelcastAtomIT.java` | 131 | same pattern |
| `substrate-dynamodb/src/test/.../atom/DynamoDbAtomIT.java` | 241 | same pattern |
| `substrate-cassandra/src/test/.../atom/CassandraAtomSpiIT.java` | 250 | same pattern |
| `substrate-core/src/test/.../memory/atom/InMemoryAtomSpiTest.java` | 197 | same pattern |

## Acceptance criteria

- [ ] Each of the 7 flagged test methods contains at least one
      assertion — either `assertThatNoException().isThrownBy(...)`
      wrapping the call(s) under test, or a positive state assertion
      like `assertThat(spi.read(key)).isEmpty()`.
- [ ] No existing assertions are removed or weakened in the process.
- [ ] The behavioral intent of each test is preserved — do not add
      new guarantees beyond what the method name suggests.
- [ ] `./mvnw verify` passes locally.
- [ ] SonarCloud `java:S2699` BLOCKER count for this project drops
      from 7 to 0 on the next scan.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Prefer `assertThatNoException().isThrownBy(() -> ...)` for tests
  whose name clearly signals "does not throw" (e.g., `deleteIdempotent`,
  `closeDoesNotThrow`). It's the minimal diff and the intent is
  unambiguous.
- For tests whose name suggests a positive postcondition
  (e.g., `deleteRemovesAtom`), the stronger form is better — look
  for the obvious state check the test was implicitly relying on
  and make it explicit.
- Do NOT add `assertThatNoException` to tests that already have
  meaningful assertions but were flagged by Sonar for other reasons —
  S2699 only fires when the tool literally finds no assertion in the
  method body. If you see a flagged test that already has an
  assertion, investigate before editing (may be a Sonar false
  positive or a test that relies on an assertion inside a helper).
- The AssertJ import is
  `import static org.assertj.core.api.Assertions.assertThatNoException;`
  — add it if the test file doesn't already have it.
