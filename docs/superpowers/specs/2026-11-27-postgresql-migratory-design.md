# PostgreSQL schema through migratory — design

Target: substrate 0.12.0. Module: `substrate-postgresql` only.

## Intent

Make rolling the schema forward easy for substrate's users. Today each PostgreSQL primitive runs a
`CREATE TABLE IF NOT EXISTS` script at startup (`auto-create-schema=true`). That creates tables once
and never evolves them, which is why 0.9.0's journal schema change shipped as "drop the tables and
let the script rebuild them". With a Liquibase changelog that [migratory](https://github.com/jwcarman/migratory)
discovers and runs, each schema change ships as a new changeset, and Liquibase applies whatever a
user's database is missing, including across skipped releases.

## Goals

- `substrate-postgresql` declares its changelog to migratory; applications list nothing.
- Databases created by substrate 0.9.0–0.11.0 adopt the changelog with no manual step.
- Every future schema change is a new changeset that rolls forward on upgrade.
- Users without Spring Boot can still roll forward from what the jar ships.

## Non-goals

- Cassandra and DynamoDB keep creating their own tables through their drivers. migratory and
  Liquibase don't cover them.
- No schema change in this release: the first changesets produce exactly today's tables.
- No migration from pre-0.9.0 journal tables (`substrate_journal_completed`). 0.9.0 already
  required dropping those.

## Anti-goals

- A second path that creates tables without Liquibase. It would go stale the way the scripts did.
- Any changeset that drops or renames something a running older instance uses.

## Constraints

- migratory 0.1.0: Java 25+, Spring Boot 4.1+ with Liquibase. Substrate is on Java 25 and Boot
  4.1.1, so it qualifies.
- migratory's changelog rules: a released changeset is never edited; the changelog file never moves;
  changes only add (new tables, nullable columns, indexes). A removal is a two-release change
  called out in the upgrade notes.
- A jar has one `META-INF/migratory.properties`, so one changelog covers all three primitives.

## Approaches

- **A. migratory only (chosen).** Depend on `migratory-autoconfigure`, declare the changelog,
  remove `auto-create-schema` and the startup scripts. It's the only option where every user rolls
  forward.
  - Within A, depending on `migratory-autoconfigure`, not `migratory-spring-boot-starter`, was a
    separate decision. A starter is for applications. Depending on it from a library would put
    Liquibase on every PostgreSQL user's class path and take away the option of running without
    Liquibase at all. `migratory-autoconfigure` keeps Liquibase optional and fails startup clearly
    when it is needed but missing.
  - Shipping only the declaration file and changelog, with no migratory dependency, was rejected:
    an application without migratory would start fine and then fail on its first query against
    tables that don't exist.
- B. migratory plus the old scripts as a fallback without Liquibase. Rejected: the fallback can only
  create, never evolve, so every schema change would need to be kept in step in two places, which is
  the trap behind 0.9.0.
- C. Ship a changelog for applications to include themselves. Rejected: every application has to
  include it, in order, and remember on upgrade, which is what migratory exists to remove.

## Design

- **Dependency:** `substrate-postgresql` depends on `org.jwcarman.migratory:migratory-autoconfigure`
  (0.1.0, compile scope, not optional), with its version managed in `substrate-parent`. It does not
  bring Liquibase: the application adds `spring-boot-starter-liquibase` to have migratory run
  substrate's changelog. Tests in `substrate-postgresql` add `spring-boot-starter-liquibase` in
  test scope.
- **Declaration:** `substrate-postgresql/src/main/resources/META-INF/migratory.properties`:
  ```properties
  name=substrate-postgresql
  changelog=classpath:db/substrate/postgresql/substrate-postgresql-changelog.xml
  ```
  No `after` or `before`: substrate references no other library's tables.
- **Changelog:** `db/substrate/postgresql/substrate-postgresql-changelog.xml`, one changeset per
  primitive (`atom-1`, `journal-1`, `mailbox-1`, author `substrate`). Each runs today's
  `V1__create_*.sql` through `sqlFile`, unchanged. Because those scripts are all `IF NOT EXISTS`, a
  database that already has the tables gets them recorded as run, so nothing needs adopting by hand.
  Future changes add `V2__…` SQL files and changesets referencing them. The SQL files keep their
  names and contents forever.
- **Removed:** the `auto-create-schema` properties (the `autoCreateSchema` record components and the
  defaults) and the `ResourceDatabasePopulator` calls in the three primitives' auto-configurations.
  A user who sets the property gets nothing (Spring Boot ignores it), and the upgrade notes say so.
- **All three primitives' tables are created** even when one is disabled with
  `substrate.postgresql.<primitive>.enabled=false`. That's a consequence of one changelog per jar.
  It's documented, not worked around.
- **What an application gets, by what it does:**

  | The application... | Result |
  |---|---|
  | adds `spring-boot-starter-liquibase` | migratory creates and rolls forward substrate's tables before the application's own changelog runs |
  | sets `migratory.enabled=false`, without Liquibase | starts; the application owns the schema, applying the jar's `V*__*.sql` files itself or with its own tool |
  | sets `migratory.enabled=false`, with Liquibase | starts; the application includes substrate's changelog in its own, and switching back reruns nothing because Liquibase records the same path |
  | does neither | fails startup with migratory's message naming both fixes, rather than failing later on missing tables |

  migratory skips its missing-Liquibase failure when `migratory.enabled` is false
  (`MigratoryAutoConfiguration`), which is what makes the second row work.
- **Without Spring Boot:** apply the jar's `V*__*.sql` files in version order, or run the changelog
  with their own Liquibase.

## Documentation and upgrade notes (0.12.0)

- `docs/backends/postgresql.md`: rewrite the Tables section to cover migratory, opting out, without
  Spring Boot, and "all three primitives' tables are created"; drop the three `auto-create-schema`
  rows from the configuration table; replace "permission to create tables" with Liquibase's needs.
- CHANGELOG `### Breaking changes`, with steps and the table above:
  1. Add `spring-boot-starter-liquibase` to the application. Spring Boot's own Liquibase run then
     needs an application changelog: add one (an empty `databaseChangeLog: []` will do), or set
     `spring.liquibase.enabled=false`, which leaves migratory running.
  2. Remove `substrate.postgresql.*.auto-create-schema` settings.
  3. Existing databases need nothing else: the first run records the existing tables.
  4. To manage the schema yourself instead, set `migratory.enabled=false`, and either include
     `classpath:db/substrate/postgresql/substrate-postgresql-changelog.xml` in your own changelog or
     apply the `V*__*.sql` files in order. Liquibase isn't needed in that case.
- A line in the contributor docs, or the changelog's header comment, stating the changelog rules
  (never edit a released changeset, never move the file, changes only add).

## Left to the builder

- Ordering: today the tables exist before each SPI bean is built, because the populator runs inside
  the bean method. Under migratory they exist once Liquibase's bean has run. Confirm nothing touches
  the tables earlier (SPI constructors, the sweeper's first run, a `SmartLifecycle` start). If
  something does, make it depend on migratory's run.

- How the ITs build their schema: through migratory in a Spring context, or by running the
  changelog with Liquibase directly in the plain-JDBC ITs. Either way, at least one test must go
  through migratory the way an application does.
- Tests to write first:
  - On an empty database, a Spring Boot context creates all four tables and records three changesets.
  - On a database whose tables were already created by today's V1 scripts, a context starts,
    records the changesets, and leaves existing rows intact.
  - A context with `migratory.enabled=false` creates no tables.
  - A context with no Liquibase on the class path fails startup with migratory's message, and with
    `migratory.enabled=false` starts. Use a filtered class loader so the test needn't remove
    Liquibase from the test class path.
- Whether the auto-configuration tests that set `auto-create-schema` just lose that property or need
  restructuring.
- Native image: check migratory's native-images guide against substrate's native-image guide and
  add what's needed.
- Whether `docs/backends/cassandra.md`'s mention of `auto-create-schema` needs touching (it's
  Cassandra's own property, so probably not).
