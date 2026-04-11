# Cover exception classes and `NextResult.isTerminal()`

## What to build

Eight files currently sit at 0% coverage and are dragging the
project metric down. None of them contain meaningful logic — they're
either exception classes with a single `super(...)` constructor or
sealed interface boilerplate — but the project aims for near-100%
coverage on all source, so each needs at least one test that
exercises its code path.

| File | Lines uncovered | Shape |
|---|---|---|
| `substrate-api/.../atom/AtomAlreadyExistsException.java` | 2 | constructor |
| `substrate-api/.../atom/AtomExpiredException.java` | 2 | constructor |
| `substrate-api/.../journal/JournalAlreadyExistsException.java` | 2 | constructor |
| `substrate-api/.../journal/JournalCompletedException.java` | 2 | constructor |
| `substrate-api/.../journal/JournalExpiredException.java` | 2 | constructor |
| `substrate-api/.../mailbox/MailboxExpiredException.java` | 2 | constructor |
| `substrate-api/.../mailbox/MailboxFullException.java` | 2 | constructor |
| `substrate-api/.../NextResult.java` | 3 | `isTerminal()` default method |

All eight live in `substrate-api`, which currently has no unit test
source directory at all (its tests are implicit in the backend
modules exercising the API). This spec creates that directory.

## What to write

### Exception constructor tests

For each exception class, write a single test method in a
dedicated test class that:

1. Constructs the exception with a key/message argument.
2. Asserts the resulting `getMessage()` contains the key.
3. Asserts the exception is throwable and catchable as its own
   type.

Example for `AtomAlreadyExistsException`:

```java
package org.jwcarman.substrate.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AtomAlreadyExistsExceptionTest {

  @Test
  void messageContainsKey() {
    AtomAlreadyExistsException ex = new AtomAlreadyExistsException("my-key");
    assertThat(ex).hasMessageContaining("my-key");
  }

  @Test
  void isThrowable() {
    assertThatThrownBy(
            () -> {
              throw new AtomAlreadyExistsException("my-key");
            })
        .isInstanceOf(AtomAlreadyExistsException.class)
        .hasMessageContaining("my-key");
  }
}
```

Keep each test class focused on exactly one exception. Seven
exception classes → seven test classes → roughly 14 tests total.

### `NextResult.isTerminal()` test

`NextResult` is a sealed interface with six record variants:
`Value`, `Timeout`, `Completed`, `Expired`, `Deleted`, `Errored`.
The `isTerminal()` default method classifies each variant:

- `Value` and `Timeout` are non-terminal (consumer can keep
  polling).
- `Completed`, `Expired`, `Deleted`, `Errored` are terminal (the
  subscription is done).

Write a single `NextResultTest` class that verifies
`isTerminal()` returns the expected value for every variant:

```java
package org.jwcarman.substrate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NextResultTest {

  @Test
  void valueIsNotTerminal() {
    assertThat(new NextResult.Value<>("v").isTerminal()).isFalse();
  }

  @Test
  void timeoutIsNotTerminal() {
    assertThat(new NextResult.Timeout<>().isTerminal()).isFalse();
  }

  @Test
  void completedIsTerminal() {
    assertThat(new NextResult.Completed<>().isTerminal()).isTrue();
  }

  @Test
  void expiredIsTerminal() {
    assertThat(new NextResult.Expired<>().isTerminal()).isTrue();
  }

  @Test
  void deletedIsTerminal() {
    assertThat(new NextResult.Deleted<>().isTerminal()).isTrue();
  }

  @Test
  void erroredIsTerminal() {
    assertThat(new NextResult.Errored<>(new RuntimeException("boom")).isTerminal()).isTrue();
  }
}
```

Six assertions, one per variant. Real test value: if a future
refactor adds a new variant and forgets to classify it, the sealed
`switch` inside `isTerminal()` will fail to compile — and if the
classification is wrong, this test will catch it.

## Acceptance criteria

### Test files

- [ ] `substrate-api/src/test/java/.../atom/AtomAlreadyExistsExceptionTest.java`
      exists with at least one test exercising the constructor.
- [ ] Same for `AtomExpiredExceptionTest`,
      `JournalAlreadyExistsExceptionTest`, `JournalCompletedExceptionTest`,
      `JournalExpiredExceptionTest`, `MailboxExpiredExceptionTest`,
      `MailboxFullExceptionTest`.
- [ ] `substrate-api/src/test/java/org/jwcarman/substrate/NextResultTest.java`
      exists and verifies `isTerminal()` for all six variants.
- [ ] Each test class is compact — aim for 1-2 tests per
      exception, 6 tests total for `NextResultTest`.

### substrate-api test wiring

- [ ] `substrate-api` now has a working
      `src/test/java/...` directory and its `pom.xml` has whatever
      test-scope dependencies it needs (JUnit Jupiter, AssertJ).
      Check the other modules' POMs for the canonical set.
- [ ] `./mvnw -pl substrate-api test` runs and passes.
- [ ] `./mvnw verify` passes from the root.

### Coverage

- [ ] After the next Sonar scan, each of the 8 files listed above
      has coverage > 90% (target: 100% except for any synthetic
      bytecode lines JaCoCo can't reach).
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- `substrate-api` may not currently have test dependencies
  declared in its POM. Check the other modules' `<dependencies>`
  sections for the JUnit Jupiter / AssertJ test-scope block and
  copy it into `substrate-api/pom.xml`.
- Do NOT combine exception tests into one file. Even though
  they're trivial, the convention across the rest of the project
  is "one test class per production class" — keep it consistent.
- Do NOT add `@SuppressWarnings("unused")` on unused exception
  variables in catch blocks. Per project policy, no suppressions.
- If an exception class has multiple constructors (e.g., one
  taking just a key, one taking key + cause), add a test for
  each.
- The `NextResult.Errored` variant takes a `Throwable cause`
  argument. Use a simple `new RuntimeException("test")` — don't
  introduce a custom test exception type.
- Exception tests are low-value in terms of what they catch, but
  they exist to satisfy the coverage goal. Do not invent
  additional behavioral assertions that Sonar wouldn't require.

## Out of scope

- Changing the exception classes themselves. They're public API
  and stable.
- Adding Javadoc to the exception classes. That's covered by
  spec 039 (public API documentation).
- Testing the sealed interface `switch` exhaustiveness itself.
  The compiler handles that at build time.
