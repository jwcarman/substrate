# Add "disabled" branch tests for backend auto-configurations

## What to build

Eight backend auto-configurations sit at exactly 50% branch
coverage with 1 uncovered branch out of 2 each:

| File |
|---|
| `substrate-cassandra/.../atom/CassandraAtomAutoConfiguration.java` |
| `substrate-cassandra/.../journal/CassandraJournalAutoConfiguration.java` |
| `substrate-dynamodb/.../atom/DynamoDbAtomAutoConfiguration.java` |
| `substrate-dynamodb/.../journal/DynamoDbJournalAutoConfiguration.java` |
| `substrate-dynamodb/.../mailbox/DynamoDbMailboxAutoConfiguration.java` |
| `substrate-postgresql/.../atom/PostgresAtomAutoConfiguration.java` |
| `substrate-postgresql/.../journal/PostgresJournalAutoConfiguration.java` |
| `substrate-postgresql/.../mailbox/PostgresMailboxAutoConfiguration.java` |

These are all in the same shape: each has a
`@ConditionalOnProperty(name = "...enabled", havingValue = "true",
matchIfMissing = true)` (or similar) on its primary `@Bean`
method, and the existing test only exercises the "enabled" path.
The "explicitly disabled" path is the missing branch.

This is a single-pattern fix repeated across 8 files.
**Spec 055 missed this entirely** because spec 055 was scoped to
"raise line coverage on Postgres auto-configs" without explicitly
checking branch coverage. Spec 061 fixes the gap I left in spec
055 and extends the same fix to the Dynamo and Cassandra
auto-configs that have the same shape.

## The pattern

Read one existing `*AutoConfigurationTest` to see the
`ApplicationContextRunner` setup it already has, then add a
second test that sets the disable property and asserts no bean is
created. Example:

```java
@Test
void doesNotCreateAtomFactoryWhenExplicitlyDisabled() {
  new ApplicationContextRunner()
      .withUserConfiguration(MockCqlSessionConfiguration.class)
      .withConfiguration(AutoConfigurations.of(CassandraAtomAutoConfiguration.class))
      .withPropertyValues("substrate.cassandra.atom.enabled=false")
      .run(context -> {
        assertThat(context).doesNotHaveBean(CassandraAtomSpi.class);
      });
}
```

The exact property name varies per backend — read each
auto-config class first to get the correct one. Some are
`substrate.<backend>.<primitive>.enabled`, others are flatter
like `substrate.<backend>.enabled`.

## Acceptance criteria

For each of the 8 files listed above:

- [ ] A new test method exists in the corresponding
      `*AutoConfigurationTest` that exercises the
      `enabled=false` branch and asserts the SPI bean is NOT
      created.
- [ ] The test uses the same `ApplicationContextRunner` /
      `AutoConfigurations.of(...)` pattern as the existing tests
      in that file. Do NOT start a real backend container.
- [ ] If the auto-config has additional `@Bean` methods (e.g., a
      Sweeper alongside the SPI), the test asserts none of them
      are created either.
- [ ] On the next Sonar scan, all 8 files have:
  - line coverage > 90% (some are at 87.5% currently — the new
    test should bump them to 100%)
  - branch coverage at 100% (the only uncovered branch is the
    one this spec adds)

### Build

- [ ] `./mvnw verify` passes from the root.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Drive from JaCoCo to find the exact uncovered branch in each
  auto-config:
  ```bash
  ./mvnw -pl substrate-<module> verify
  open substrate-<module>/target/site/jacoco/index.html
  ```
  Drill into the auto-config class to see the red branch. The
  branch is almost always on the `@ConditionalOnProperty`
  conditional, but verify before writing the test.
- The disable-property name comes from the
  `@ConditionalOnProperty` annotation on the auto-config class
  itself or on its primary `@Bean` method. Read the class to
  get the exact name — DON'T guess.
- If an auto-config has more than one `@ConditionalOnProperty`
  (e.g., separate properties for atom vs sweeper), the test
  needs to disable the right one and assert what isn't created.
  Match the granularity of the conditional.
- Do NOT add `@ConditionalOnMissingBean` tests in this spec —
  those test a different concern (user-provided override) and
  aren't what's flagged.
- Some `*AutoConfigurationTest` classes already exist; this
  spec adds methods to them. Do NOT create new test classes
  unless an auto-config has no test class at all.
- The `Postgres*AutoConfigurationTest` classes likely already
  passed once under spec 055. Spec 055 was supposed to bring
  them to peer parity but missed the branch coverage. This spec
  adds the missing test to each.
