# Bring Postgres auto-config coverage to peer parity

## What to build

The three Postgres auto-configurations sit well below the peer
backends' coverage levels:

| File | Current | Peer average |
|---|---|---|
| `PostgresAtomAutoConfiguration.java` | 55.6% | 87–90% |
| `PostgresMailboxAutoConfiguration.java` | 55.6% | 87–90% |
| `PostgresJournalAutoConfiguration.java` | 60.0% | 87–90% |

Each has 3 uncovered lines. The peer backends (Cassandra,
DynamoDB, Mongo, Hazelcast) all have 87.5–90% coverage on their
equivalent auto-configs, so the gap is fixable without any new
test infrastructure — the pattern just needs to be applied.

Read the three Postgres auto-config classes, identify what each
peer test is covering that the Postgres tests are missing, and
add the equivalent coverage.

## Likely gaps

Without reading the files yet, the most common uncovered lines
in Spring Boot auto-configs are:

1. **`@ConditionalOnProperty` negative branches** — tests
   exercise the happy path where the property is set, but never
   the "property set to false" path where the bean is NOT
   created. Peer backends add a second test for this.
2. **`@ConditionalOnMissingBean` branches** — user-provided bean
   overrides the auto-config's bean. Peer backends usually skip
   this one.
3. **Secondary `@Bean` methods** — an auto-config that creates
   multiple beans (e.g., the SPI plus a Sweeper) may have the
   secondary bean creation path uncovered.
4. **Property binding** — `@EnableConfigurationProperties(XxxProperties.class)`
   pulls in a `@ConfigurationProperties` class; the test may
   not exercise all property paths.

Read each `PostgresXxxAutoConfigurationTest.java` and compare
line-by-line with the Cassandra/Mongo equivalents to find the
gap.

## Acceptance criteria

- [ ] `PostgresAtomAutoConfigurationTest` covers whatever
      branches are currently missed — target 90%+ coverage on
      `PostgresAtomAutoConfiguration.java`.
- [ ] Same for `PostgresJournalAutoConfigurationTest` →
      `PostgresJournalAutoConfiguration.java`.
- [ ] Same for `PostgresMailboxAutoConfigurationTest` →
      `PostgresMailboxAutoConfiguration.java`.
- [ ] All new tests use `ApplicationContextRunner` +
      `AutoConfigurations.of(...)` — do NOT start a full Spring
      Boot context or a Testcontainer.
- [ ] Mock any upstream dependencies (e.g., `DataSource`,
      `JdbcTemplate`) via a `@TestConfiguration` inner class.
- [ ] `./mvnw -pl substrate-postgresql verify` passes.
- [ ] All three files show > 90% coverage on the next Sonar scan.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Start by running
  `./mvnw -pl substrate-postgresql verify`
  and looking at the JaCoCo report in
  `substrate-postgresql/target/site/jacoco/index.html` to see
  exactly which lines in each Postgres auto-config are red.
  That's faster than inspecting the code manually.
- For comparison, look at
  `substrate-cassandra/src/test/java/.../atom/CassandraAtomAutoConfigurationTest.java`
  and the equivalent Mongo test. Figure out what tests they have
  that Postgres doesn't, and port the pattern over.
- If a conditional property in the auto-config has no
  corresponding disabled-test, that's almost certainly the gap.
  Add the negative test.
- Do NOT refactor the auto-configuration classes themselves in
  this spec. Scope is tests only. If the auto-config has
  awkward-to-test code (e.g., a lambda that's hard to exercise),
  note it but don't rewrite it — file a separate concern.
- Do NOT add Spring Boot context-load tests that test Spring
  Boot itself. The test should exercise this auto-config's
  specific wiring, nothing more.
