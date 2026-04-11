# Cover `JournalEntry` and `Snapshot` records

## What to build

Two public records in `substrate-api` sit at 0% coverage:

| File | Uncovered |
|---|---|
| `substrate-api/.../journal/JournalEntry.java` | 1 line |
| `substrate-api/.../atom/Snapshot.java` | 1 line |

Both are public API records used throughout the project but never
directly exercised by a test in `substrate-api`'s own test tree.
Spec 053 wired up the test source directory in `substrate-api`;
this spec adds the trivial coverage for these two records.

## What to write

Each test file should be small — one or two tests verifying that
the record's accessors return the values passed to the constructor
and that `equals`/`hashCode`/`toString` behave reasonably for the
component types.

### `JournalEntryTest`

```java
package org.jwcarman.substrate.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

  @Test
  void accessorsReturnConstructorArguments() {
    Instant ts = Instant.parse("2026-04-11T00:00:00Z");
    JournalEntry<String> entry = new JournalEntry<>("entry-id", "payload", ts);

    assertThat(entry.id()).isEqualTo("entry-id");
    assertThat(entry.value()).isEqualTo("payload");
    assertThat(entry.timestamp()).isEqualTo(ts);
  }

  @Test
  void equalsAndHashCodeMatchForIdenticalContent() {
    Instant ts = Instant.parse("2026-04-11T00:00:00Z");
    JournalEntry<String> a = new JournalEntry<>("id", "v", ts);
    JournalEntry<String> b = new JournalEntry<>("id", "v", ts);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }
}
```

(Read the actual `JournalEntry` record's component list before
writing — the components above are a guess based on the name.)

### `SnapshotTest`

```java
package org.jwcarman.substrate.atom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SnapshotTest {

  @Test
  void accessorsReturnConstructorArguments() {
    Snapshot<String> snap = new Snapshot<>("hello", "token-1");

    assertThat(snap.value()).isEqualTo("hello");
    assertThat(snap.token()).isEqualTo("token-1");
  }

  @Test
  void equalsAndHashCodeMatchForIdenticalContent() {
    Snapshot<String> a = new Snapshot<>("v", "t");
    Snapshot<String> b = new Snapshot<>("v", "t");

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }
}
```

## Acceptance criteria

- [ ] `substrate-api/src/test/java/.../journal/JournalEntryTest.java`
      exists with at least the two tests above (adapted to the
      record's actual component list).
- [ ] `substrate-api/src/test/java/.../atom/SnapshotTest.java`
      exists with at least the two tests above.
- [ ] Both files use the AssertJ idiomatic forms:
      `hasSameHashCodeAs(other)` instead of comparing hashCodes
      directly, and chained assertions where applicable.
- [ ] `./mvnw -pl substrate-api verify` passes.
- [ ] After the next Sonar scan, `JournalEntry.java` and
      `Snapshot.java` show > 90% coverage (target 100%).
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Read the actual record definitions before writing the tests.
  The component list (`id`/`value`/`timestamp` for JournalEntry,
  `value`/`token` for Snapshot) is what I'd expect, but the real
  components are what should drive the test.
- These records are generic over their value type. Use `String`
  in the tests for simplicity — no need to test multiple type
  parameters.
- Do NOT add `toString` assertions unless one of these records
  has a custom `toString` override (neither one does as far as I
  know — the default record toString is fine).
