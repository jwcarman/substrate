# PostgreSQL Schema Through migratory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `substrate-postgresql` creates and rolls forward its tables through a Liquibase changelog
that migratory discovers, replacing the `auto-create-schema` startup scripts.

**Architecture:** The module declares one changelog in `META-INF/migratory.properties`. The
changelog has one `sqlFile` changeset per primitive, each running today's unchanged `V1__*.sql`, so
existing databases adopt it with no manual step. The auto-configurations stop running DDL and mark
their SPI beans `@DependsOnDatabaseInitialization`, so nothing touches the tables before migratory
has run.

**Tech Stack:** Java 25, Spring Boot 4.1.1, migratory 0.1.0 (`migratory-autoconfigure`), Liquibase
(test scope via `spring-boot-starter-liquibase`), Testcontainers PostgreSQL 16, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-27-postgresql-migratory-design.md`

## Global Constraints

- Work on a branch, `postgresql-migratory`, off `main` (pom is `0.12.0-SNAPSHOT`).
- Dependency is `org.jwcarman.migratory:migratory-autoconfigure` 0.1.0, compile scope, not
  optional. Never `migratory-spring-boot-starter`. Version is `${migratory.version}`, managed in the
  root `pom.xml`'s `dependencyManagement`.
- `spring-boot-starter-liquibase` is **test** scope only in `substrate-postgresql`.
- The three `V1__create_*.sql` files are not edited, renamed or moved, now or ever.
- The changelog is `db/substrate/postgresql/substrate-postgresql-changelog.xml`, and it never moves.
- Declaration: `name=substrate-postgresql`, `changelog=classpath:db/substrate/postgresql/substrate-postgresql-changelog.xml`, with no `after` or `before`.
- Changesets: `atom-1`, `journal-1`, `mailbox-1`, author `substrate`.
- Cassandra and DynamoDB are untouched.
- Native images need no substrate hints. migratory's `process-aot` step registers everything under
  the declared changelog's directory, so the SQL files must stay beside the changelog in
  `db/substrate/postgresql/`.
- Repo rules: no warning suppression, no star imports, no fully-qualified names in code, JUnit 5
  snake_case method names with `@DisplayNameGeneration(ReplaceUnderscores.class)` and `@Nested`
  groups, spotless-formatted, license header on new Java files (copy from any existing file).
- Commit messages end with the attribution lines in the session's system reminder.

## Review Focus

1. **A database created by 0.9.0–0.11.0 that already holds rows.** Expect startup to record the
   changesets and keep every row. Pinned by Task 1's `adopts_tables_created_by_the_v1_scripts`.
2. **Sweeper or SPI touching tables before migratory runs.** The tables don't exist yet on a fresh
   database. Expect SPI beans, and so the sweepers built from them, to be created after migratory.
   Pinned by Task 2's `spi_beans_wait_for_database_initialization`.
3. **Liquibase missing and migratory left on.** Expect migratory's startup failure naming
   `substrate-postgresql`, not a later "relation does not exist". Pinned by Task 1's
   `without_liquibase` tests.
4. **Starting a second time against an already-migrated database.** Expect nothing reruns and no
   checksum failure. Pinned by Task 1's `a_second_start_runs_nothing`.
5. **A user still setting `auto-create-schema=true` after upgrading.** Expect it to be ignored
   (Spring Boot ignores unknown properties) and the tables to come from migratory. Covered by the
   CHANGELOG note in Task 3. No test, because asserting that an unknown property is ignored only
   tests Spring Boot.

---

### Task 1: Declare the changelog to migratory

**Files:**
- Modify: `pom.xml` (root): add `<migratory.version>0.1.0</migratory.version>` beside
  `codec.version`, and a `dependencyManagement` entry for `migratory-autoconfigure`.
- Modify: `substrate-postgresql/pom.xml`: add `migratory-autoconfigure` (compile) and
  `spring-boot-starter-liquibase` (test).
- Create: `substrate-postgresql/src/main/resources/META-INF/migratory.properties`
- Create: `substrate-postgresql/src/main/resources/db/substrate/postgresql/substrate-postgresql-changelog.xml`
- Test: `substrate-postgresql/src/test/java/org/jwcarman/substrate/postgresql/PostgresSchemaMigrationIT.java`

**Interfaces:**
- Produces: the changelog path and changeset ids above, used by Tasks 2 and 3.
- Produces (test-only, inside the IT): `String freshDatabaseUrl()`. It runs
  `CREATE DATABASE substrate_<random hex>` on an admin connection to `PostgresTestContainer.INSTANCE`
  and returns a JDBC URL to it (same host and mapped port as `getJdbcUrl()`). Every test uses its
  own database, so tests never see each other's tables or Liquibase history.

The IT builds contexts with `ApplicationContextRunner` and
`AutoConfigurations.of(DataSourceAutoConfiguration.class, LiquibaseAutoConfiguration.class, MigratoryAutoConfiguration.class, PostgresAutoConfiguration.class, PostgresAtomAutoConfiguration.class, PostgresJournalAutoConfiguration.class, PostgresMailboxAutoConfiguration.class)`
with `spring.datasource.url=<freshDatabaseUrl()>`, the container's username and password,
`spring.liquibase.enabled=false` (the module has no application changelog; migratory still runs),
and `substrate.postgresql.notifier.enabled=false`. Boot 4 packages:
`org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`,
`org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration`,
`org.jwcarman.migratory.autoconfigure.MigratoryAutoConfiguration`.

- [ ] **Step 1: Create the branch**

Run: `git switch -c postgresql-migratory`

- [ ] **Step 2: Add the dependencies** (root `pom.xml` and `substrate-postgresql/pom.xml` as listed
  above), then run `./mvnw -q -pl substrate-postgresql -am compile`. Expected: BUILD SUCCESS.

- [ ] **Step 3: Write the failing IT.** Use `@Nested` groups `On_an_empty_database`,
  `On_a_database_from_the_v1_scripts`, `Without_liquibase`. Helpers:
  `Set<String> substrateTables(String url)` returns the names from `information_schema.tables` where
  `table_name LIKE 'substrate\_%'`. `List<String> recordedChangesets(String url)` returns the ids from
  `databasechangelog` where `filename LIKE '%substrate-postgresql-changelog.xml'`, ordered by
  `orderexecuted`.

```java
// On_an_empty_database
@Test void creates_every_table_and_records_three_changesets() {
  // run context on fresh url; then:
  assertThat(substrateTables(url)).containsExactlyInAnyOrder(
      "substrate_atom", "substrate_journal", "substrate_journal_entries", "substrate_mailbox");
  assertThat(recordedChangesets(url)).containsExactly("atom-1", "journal-1", "mailbox-1");
}
@Test void a_second_start_runs_nothing() {
  // run the context twice on the same url, both start without failure; then:
  assertThat(recordedChangesets(url)).containsExactly("atom-1", "journal-1", "mailbox-1");
}

// On_a_database_from_the_v1_scripts
@Test void adopts_tables_created_by_the_v1_scripts() {
  // before the context: run the three V1__ scripts with ResourceDatabasePopulator, then
  // INSERT INTO substrate_atom (key, value, token, expires_at)
  //   VALUES ('substrate:atom:kept', '\x01', 't', now() + interval '1 hour')
  // run the context; then:
  assertThat(recordedChangesets(url)).containsExactly("atom-1", "journal-1", "mailbox-1");
  assertThat(count(url, "SELECT count(*) FROM substrate_atom WHERE key = 'substrate:atom:kept'"))
      .isEqualTo(1);
}

// Without_liquibase  — runner.withClassLoader(new FilteredClassLoader("liquibase."))
@Test void fails_startup_naming_substrate_postgresql() {
  // assertThat(context).hasFailed();
  // assertThat(context.getStartupFailure()).rootCause().message()
  //     .contains("substrate-postgresql").contains("spring-boot-starter-liquibase");
}
@Test void starts_when_migratory_is_disabled() {
  // with migratory.enabled=false: assertThat(context).hasNotFailed();
}
```

- [ ] **Step 4: Run it to see it fail**

Run: `./mvnw -pl substrate-postgresql verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='PostgresSchemaMigrationIT*' -Djacoco.skip=true`
Expected: the three changeset assertions fail with an empty `databasechangelog` (or the table is
missing), and `fails_startup_naming_substrate_postgresql` fails because the context started. With
no declaration, migratory has nothing to require Liquibase for. `starts_when_migratory_is_disabled`
may already pass.

- [ ] **Step 5: Write `META-INF/migratory.properties`** with the two keys from Global Constraints.

- [ ] **Step 6: Write the changelog.** It's a standard `databaseChangeLog` with the
  `http://www.liquibase.org/xml/ns/dbchangelog` namespace and the `dbchangelog-latest.xsd` schema
  location. Three changesets in this order: `atom-1`, `journal-1`, `mailbox-1`, author `substrate`.
  Each holds one `<sqlFile path="V1__create_<primitive>.sql" relativeToChangelogFile="true" splitStatements="true" stripComments="true"/>`.
  Put an XML comment at the top stating the rules: a released changeset is never edited; this file
  and the SQL files never move or get renamed; changes only add (new tables, nullable columns,
  indexes); a new change is a new `V<n>__*.sql` file in this directory plus a new changeset.

- [ ] **Step 7: Run the IT to see it pass.** Same command as Step 4. Expected: all five tests pass.

- [ ] **Step 8: Run the module build.** Run `./mvnw -pl substrate-postgresql verify`. Expected:
  BUILD SUCCESS. The existing ITs still apply the V1 scripts directly and are unaffected.

- [ ] **Step 9: Commit**

```bash
git add pom.xml substrate-postgresql/pom.xml substrate-postgresql/src/main/resources/META-INF/migratory.properties substrate-postgresql/src/main/resources/db/substrate/postgresql/substrate-postgresql-changelog.xml substrate-postgresql/src/test/java/org/jwcarman/substrate/postgresql/PostgresSchemaMigrationIT.java
git commit -m "feat(postgresql): declare the schema changelog to migratory"
```

---

### Task 2: Remove auto-create-schema and order the SPIs after migratory

**Files:**
- Modify: `substrate-postgresql/src/main/java/org/jwcarman/substrate/postgresql/PostgresProperties.java`.
  Drop `autoCreateSchema` from `AtomProperties`, `JournalProperties` and `MailboxProperties`.
- Modify: `substrate-postgresql/src/main/resources/substrate-postgresql-defaults.properties`. Delete
  the three `auto-create-schema` lines.
- Modify: `.../postgresql/atom/PostgresAtomAutoConfiguration.java`,
  `.../journal/PostgresJournalAutoConfiguration.java`,
  `.../mailbox/PostgresMailboxAutoConfiguration.java`. Delete the `ResourceDatabasePopulator`
  block, and annotate the bean method with
  `@DependsOnDatabaseInitialization` (`org.springframework.boot.sql.init.dependency`).
- Modify: `PostgresAtomAutoConfigurationTest`, `PostgresJournalAutoConfigurationTest`,
  `PostgresMailboxAutoConfigurationTest`. Delete the `auto-create-schema=false` property values;
  nothing else changes.
- Test: `PostgresSchemaMigrationIT` (from Task 1)

**Interfaces:**
- Consumes: Task 1's IT, its context runner setup and `freshDatabaseUrl()`.
- Produces: record shapes `AtomProperties(boolean enabled, String prefix)`,
  `JournalProperties(boolean enabled, String prefix, long maxLen)`,
  `MailboxProperties(boolean enabled, String prefix)`.

- [ ] **Step 1: Add the failing tests to `PostgresSchemaMigrationIT`.** Add a `@Nested` group
  `With_migratory_disabled`:

```java
@Test void creates_no_tables() {
  // context on a fresh url with migratory.enabled=false (Liquibase present); then:
  assertThat(substrateTables(url)).isEmpty();
}
```

Add a `@Nested` group `Bean_ordering`:

```java
@Test void spi_beans_wait_for_database_initialization() {
  // context on a fresh url; for each of PostgresAtomSpi, PostgresJournalSpi, PostgresMailboxSpi:
  //   String name = context.getBeanNamesForType(type)[0];
  //   assertThat(context.getBeanFactory().getBeanDefinition(name).getDependsOn())
  //       .as(name).contains(<the bean name of the MigratoryLiquibase bean>);
}
```

Look up the `MigratoryLiquibase` bean name with
`context.getBeanNamesForType(MigratoryLiquibase.class)[0]`.

- [ ] **Step 2: Run them to see them fail.** Same command as Task 1 Step 4. Expected:
  `creates_no_tables` fails listing the four tables, which today's auto-create path created.
  `spi_beans_wait_for_database_initialization` fails with `getDependsOn()` null or missing the
  migratory bean.

- [ ] **Step 3: Make the source changes** listed under Files.

- [ ] **Step 4: Run the IT to see it pass.** Same command. Expected: every test passes.

- [ ] **Step 5: Run the module build.** Run `./mvnw -pl substrate-postgresql verify`. Expected:
  BUILD SUCCESS. Then `git grep -n -i "auto-create-schema\|autoCreateSchema" -- substrate-postgresql`
  prints nothing.

- [ ] **Step 6: Commit**

```bash
git add substrate-postgresql
git commit -m "feat(postgresql)!: tables come from migratory; auto-create-schema removed"
```

---

### Task 3: Documentation and upgrade notes

**Files:**
- Modify: `docs/backends/postgresql.md`. In "What it needs", replace "Permission to create tables"
  with: Liquibase (`spring-boot-starter-liquibase`) and permission to create tables, unless you
  manage the schema yourself. Rewrite "Tables" to cover: migratory creating and rolling forward the
  tables before the application's own changelog; all three primitives' tables being created even
  when one is disabled; the spec's four-row "What an application gets" table; opting out with
  `migratory.enabled=false` (include `classpath:db/substrate/postgresql/substrate-postgresql-changelog.xml`,
  or apply the `V*__*.sql` files in version order); without Spring Boot. Delete the three
  `auto-create-schema` rows from the configuration table.
- Modify: `CHANGELOG.md` under `## [Unreleased]`: `### Breaking changes`, with the spec's four
  upgrade steps verbatim in meaning, including the empty `databaseChangeLog: []` example and the
  `spring.liquibase.enabled=false` alternative.
- Modify: any other page `git grep -n -i "auto-create-schema\|V1__create\|create its tables" -- docs README.md ':!docs/superpowers/*' ':!docs/plans/*'`
  finds for PostgreSQL. `docs/backends/cassandra.md` refers to Cassandra's own property and stays.

- [ ] **Step 1: Make the edits** listed above. Docs are prose and get no tests.

- [ ] **Step 2: Check the site builds**

Run: `git grep -n "mkdocs" .github/workflows/docs.yml` to find the build command. Run it locally
if `mkdocs` is installed (`mkdocs build --strict`); otherwise note that it was skipped.
Expected: no warnings about broken links or anchors.

- [ ] **Step 3: Full verification**

Run: `./mvnw verify && ./mvnw -q spotless:check && ./mvnw -B -Plicense validate`
Expected: BUILD SUCCESS for each.

- [ ] **Step 4: Commit**

```bash
git add docs/backends/postgresql.md CHANGELOG.md
git commit -m "docs(postgresql): schema through migratory; upgrade notes for 0.12.0"
```
