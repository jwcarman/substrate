# PostgreSQL

`substrate-postgresql` stores all three primitives in PostgreSQL tables and carries
notifications with `LISTEN` and `NOTIFY`. With it, an application that already has a database
needs nothing else. It is also the backend that implements the whole
[Journal lifecycle](../primitives/journal.md#how-backends-differ).

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-postgresql</artifactId>
</dependency>
```

## What it needs

- **A `DataSource`** for a PostgreSQL database — the one Spring Boot configures from
  `spring.datasource.*` — and the PostgreSQL JDBC driver.
- **Liquibase**, through `spring-boot-starter-liquibase`, and permission to create tables, unless
  you manage the schema yourself (below).
- PostgreSQL 16 is what Substrate's tests run against.

The notifier keeps one connection from the `DataSource` open for `LISTEN`, so size the pool for
one extra connection per node.

## Tables

Substrate's PostgreSQL tables come from a Liquibase changelog in the jar, which
[migratory](https://jwcarman.github.io/migratory/) finds and runs at startup, before your
application's own Liquibase changelog:

| Primitive | Tables |
|---|---|
| Atom | `substrate_atom` |
| Journal | `substrate_journal`, `substrate_journal_entries` |
| Mailbox | `substrate_mailbox` |

When a release changes the schema, it ships the change as a new changeset, and the next start
applies whatever your database is missing, even if you skipped releases. Changes only add tables,
nullable columns and indexes, so instances still on the previous release keep working during a
rolling deploy.

All three primitives' tables are created, even when you turn one off with
`substrate.postgresql.<primitive>.enabled=false`.

### Setting it up

Add Liquibase to your application:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-liquibase</artifactId>
</dependency>
```

Spring Boot's own Liquibase run then expects a changelog of your application's own. Add one (an
empty `databaseChangeLog: []` will do), or set `spring.liquibase.enabled=false`, which leaves
migratory running.

| Your application... | What happens |
|---|---|
| adds `spring-boot-starter-liquibase` | migratory creates and rolls forward Substrate's tables before your own changelog runs |
| sets `migratory.enabled=false`, without Liquibase | starts; you own the schema and create it with your own tool |
| sets `migratory.enabled=false`, with Liquibase | starts; you include Substrate's changelog in your own (below) |
| does neither | fails at startup with a message naming both fixes |

### Managing the schema yourself

Set `migratory.enabled=false` and include Substrate's changelog in yours:

```yaml
databaseChangeLog:
  - include:
      file: classpath:org/jwcarman/substrate/postgresql/db/changelog.xml
```

Liquibase records the changesets under the same paths either way, so you can switch between
migratory and your own changelog without anything running twice. To create the schema with
another tool, generate the SQL with `liquibase update-sql` against that changelog.

### Without Spring Boot

Run `classpath:org/jwcarman/substrate/postgresql/db/changelog.xml` with Liquibase, or generate SQL
from it with `liquibase update-sql`.

## How it stores each primitive

| Primitive | Storage |
|---|---|
| Atom | One row per atom with its value, token and `expires_at`. Compare-and-set is a conditional `UPDATE`. |
| Journal | A row per journal (its inactivity TTL, completion and deadline) and a row per entry, ordered by a sequence ID. |
| Mailbox | One row per mailbox; delivery succeeds only if nothing has been delivered. |
| Notifier | `pg_notify` on the channel `substrate_notify`; each node `LISTEN`s on one connection. |

## Expiry and sweeping

PostgreSQL has no native expiry. Reads ignore rows whose deadline has passed, and Substrate's
[sweeper](../guides/operating.md#sweeping) deletes them in the background — by default every
minute, 1,000 rows at a time, using `FOR UPDATE SKIP LOCKED` so the sweepers on different nodes
don't contend. Keep the sweeper enabled with this backend; without it, expired rows accumulate.

Two consequences of sweeping:

- **An expired atom keeps its name until it is swept.** `AtomFactory.create` on that name throws
  `AtomAlreadyExistsException` for up to one sweep interval after expiry.
- **Deadlines use two clocks.** They are computed from the application's clock when written and
  compared with the database's clock when read. Keep server clocks synchronised; any skew
  lengthens or shortens every TTL by that amount.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.postgresql.atom.enabled` | `true` | Serve Atom from PostgreSQL. |
| `substrate.postgresql.atom.prefix` | `substrate:atom:` | Prefix of the stored key. |
| `substrate.postgresql.journal.enabled` | `true` | Serve Journal from PostgreSQL. |
| `substrate.postgresql.journal.prefix` | `substrate:journal:` | Prefix of the stored key. |
| `substrate.postgresql.journal.max-len` | `100000` | Approximate maximum entries per journal. Trimming runs every 100 appends per node, so a journal can exceed it for a while. |
| `substrate.postgresql.mailbox.enabled` | `true` | Serve Mailbox from PostgreSQL. |
| `substrate.postgresql.mailbox.prefix` | `substrate:mailbox:` | Prefix of the stored key. |
| `substrate.postgresql.notifier.enabled` | `true` | Use `LISTEN`/`NOTIFY` as the notifier. |
| `substrate.postgresql.notifier.channel` | `substrate_notify` | The notification channel. |

## Behaviour to know

- **Notifications reach every node listening on the channel.** If the listening connection drops, the notifier reconnects; notifications sent while it
  was down are lost, and subscriptions catch up on their next once-a-second read.
- **Every subscription polls the database.** Each active subscription reads its row about once a
  second. Many thousands of concurrent subscriptions mean many thousands of small queries per
  second; see [Operating in Production](../guides/operating.md).

## Local development

```bash
docker run -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:16-alpine
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres
    username: postgres
    password: postgres
```
