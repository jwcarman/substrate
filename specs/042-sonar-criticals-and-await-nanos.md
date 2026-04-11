# Fix Sonar criticals and awaitNanos return-value bugs

## What to build

Three small, targeted fixes from the current SonarCloud scan:

1. **`java:S1192` CRITICAL × 2** — duplicated string literals that
   should be extracted to `private static final` constants.
2. **`java:S899` MINOR bug × 2** — `Condition.awaitNanos` return
   value ignored in two handoff classes.

None of these require behavior changes. The `awaitNanos` fix is
technically a no-op because the enclosing `while` loop already
recomputes the remaining deadline each iteration, but satisfying
the rule makes the deadline semantics more obvious to readers.

## The 4 sites

### S1192 CRITICAL — extract string constants

1. **`substrate-core/.../core/subscription/FeederSupport.java`**
   - The literal `"Feeder '"` appears 3 times across the DEBUG start
     log, WARN error log, and DEBUG exit log (all added in spec
     040). Extract a `private static final String` constant —
     suggested name: `LOG_PREFIX` or just use a pre-built format
     string like `private static final String FEEDER_LABEL_FMT = "Feeder '%s' for key '%s'";`
     and pass name+key through `String.format`.
   - The cleanest fix is a small helper method
     `private static String feederLabel(String threadName, String key)`
     returning `"Feeder '" + threadName + "' for key '" + key + "'"`.
     Callers become `log.debug(feederLabel(threadName, key) + " started")`
     etc. Three call sites shrink and the literal appears once.

2. **`substrate-cassandra/.../cassandra/atom/CassandraAtomSpi.java`**
   - The literal `"[applied]"` appears 3 times. This is the
     Cassandra driver's convention for LWT result column names.
     Extract as `private static final String APPLIED_COLUMN = "[applied]";`
     and reference it at each call site.

### S899 MINOR bug — consume awaitNanos return value

3. **`substrate-core/.../core/subscription/CoalescingHandoff.java:66`**
   - Inside `pull(Duration timeout)`, the current code does:
     ```java
     while (slot == null) {
       long remaining = deadlineNanos - System.nanoTime();
       if (remaining <= 0) return new NextResult.Timeout<>();
       try {
         notEmpty.awaitNanos(remaining);
       } catch (InterruptedException e) { ... }
     }
     ```
   - Fix: assign the return value back to `remaining`:
     ```java
     remaining = notEmpty.awaitNanos(remaining);
     ```
   - The assignment is redundant at runtime because the `while` loop
     recomputes `remaining` from `deadlineNanos` on the next
     iteration, but it satisfies Sonar and documents the deadline
     contract. The loop's deadline semantics are preserved exactly.

4. **`substrate-core/.../core/subscription/SingleShotHandoff.java:67`**
   - Identical pattern, identical fix.

## Acceptance criteria

### Criticals

- [ ] `FeederSupport.java` no longer contains the literal `"Feeder '"`
      more than once. The three log call sites share a single
      source for the label.
- [ ] `CassandraAtomSpi.java` defines a `private static final String`
      constant for `"[applied]"` and references it in all three
      previous call sites.
- [ ] Neither extraction changes the runtime output of any log
      message or the Cassandra LWT column name — the literal values
      remain `"Feeder '"` and `"[applied]"` exactly.

### Bugs

- [ ] `CoalescingHandoff.pull(Duration)` assigns the return value
      of `notEmpty.awaitNanos(remaining)` (e.g., back into
      `remaining`). Behavior is unchanged because the enclosing
      `while` loop recomputes `remaining` from `deadlineNanos` on
      each iteration.
- [ ] `SingleShotHandoff.pull(Duration)` receives the same fix.
- [ ] All existing `CoalescingHandoffTest` and `SingleShotHandoffTest`
      tests continue to pass without modification.

### Build and Sonar

- [ ] `./mvnw verify` passes locally.
- [ ] SonarCloud open issue counts on the next scan:
      `java:S1192` CRITICAL drops by at least 2;
      `java:S899` drops by 2.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Do not rename the literal values themselves. `"[applied]"` is
  defined by the Cassandra driver's LWT result contract; `"Feeder '"`
  is user-facing log output and changing it would invalidate log
  scraping done by operators.
- For the `FeederSupport` extraction, a helper method is better
  than a printf-style format string because the three call sites
  append different suffixes (`" started"`, `" exited cleanly"`,
  `" caught unexpected error"`) and we don't want three separate
  format templates.
- For `awaitNanos`: do NOT try to "use" the return value by
  computing something new with it — that risks changing the
  deadline semantics. The minimal-change fix is
  `remaining = notEmpty.awaitNanos(remaining);` and nothing else.
