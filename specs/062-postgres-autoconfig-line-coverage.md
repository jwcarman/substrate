# Postgres auto-config line coverage retry (spec 055 redo)

## What to build

Spec 055 was supposed to bring the three Postgres auto-config
files to peer parity (87–90% coverage), but the next Sonar scan
showed they're still at 55–60% line coverage:

| File | Current line coverage | Uncovered |
|---|---|---|
| `PostgresAtomAutoConfiguration.java` | 55.6% | 3 |
| `PostgresMailboxAutoConfiguration.java` | 55.6% | 3 |
| `PostgresJournalAutoConfiguration.java` | 60.0% | 3 |

Spec 055 is in `done/` so Ralph believed it landed. Either:
1. The tests Ralph wrote covered the wrong lines, or
2. The acceptance criteria didn't require him to verify against
   JaCoCo before declaring done.

This spec is the redo. **Drive from the JaCoCo HTML report,
not from a generic "test the auto-config" mandate.** For each
file, identify the specific red lines and write tests that
exercise them.

Note: spec 061 covers the missing branch (the
`@ConditionalOnProperty` disabled path) for these same files. The
two specs are complementary — 061 adds the disabled-branch test,
062 covers any remaining red lines on the enabled-branch path.

## Workflow

For each of the three Postgres auto-configs:

1. Run `./mvnw -pl substrate-postgresql verify`.
2. Open `substrate-postgresql/target/site/jacoco/index.html`.
3. Drill into the auto-config class.
4. Note the red lines.
5. Look at the corresponding peer auto-config test in
   `substrate-cassandra` or `substrate-mongodb` to see how it
   covers the analogous lines.
6. Port the test pattern over.
7. Re-run verify and re-check JaCoCo until coverage > 90%.

## Likely causes of the gap

Without inspecting the files yet, the most common reasons a
Spring Boot auto-config test misses lines are:

1. **A secondary `@Bean` method not exercised.** The
   auto-config creates the SPI plus a Sweeper plus a Properties
   binding; the test only asserts the SPI bean.
2. **`DataSource` / `JdbcTemplate` wiring branches.** Postgres
   auto-configs likely have a code path like
   `if (jdbcTemplate == null) { jdbcTemplate = new JdbcTemplate(dataSource); }`
   that's only exercised when the user doesn't provide one.
3. **Properties default values.** The
   `@ConfigurationProperties` binding has defaults that aren't
   exercised because the test sets every property explicitly.

The peer backends' tests have analogous coverage and the same
shape, so porting their patterns directly should close the gap.

## Acceptance criteria

For each of the three files:

- [ ] Line coverage > 90% on the next Sonar scan.
- [ ] The corresponding `*AutoConfigurationTest` has tests for
      every `@Bean` method in the auto-config.
- [ ] If the auto-config exposes a properties bean with
      defaults, the test exercises both the "user sets value"
      and "default value" paths.
- [ ] Tests use `ApplicationContextRunner` +
      `AutoConfigurations.of(...)` — the same pattern as
      Cassandra/Mongo. No real DB.
- [ ] `./mvnw -pl substrate-postgresql verify` passes.
- [ ] `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

### Verify before declaring done

- [ ] Run `./mvnw -pl substrate-postgresql verify`, open the
      JaCoCo HTML report, and confirm visually that the previously-
      red lines in each Postgres auto-config are now green. Do
      NOT mark this spec done until JaCoCo confirms.

## Implementation notes

- Spec 055 may have already added some tests. Don't delete
  existing tests — extend them.
- The peer comparison files to read first:
  - `substrate-cassandra/.../atom/CassandraAtomAutoConfigurationTest.java`
  - `substrate-mongodb/.../atom/MongoDbAtomAutoConfigurationTest.java`
  - Whichever is closest in shape to the Postgres auto-config
    you're working on.
- If the Postgres auto-config has logic the peer backends
  don't (e.g., `JdbcTemplate` construction from a `DataSource`),
  that's where the unique coverage gap likely lives. Cassandra
  doesn't have this code path because it gets a `CqlSession`
  directly.
- Mock dependencies via `@TestConfiguration`. The Postgres
  tests will need a mock `DataSource`; provide one with Mockito
  rather than HikariCP.
- Do NOT change the auto-config classes themselves. Tests only.
