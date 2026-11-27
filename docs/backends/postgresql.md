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
- **Permission to create tables**, unless you create them yourself (below).
- PostgreSQL 16 is what Substrate's tests run against.

The notifier keeps one connection from the `DataSource` open for `LISTEN`, so size the pool for
one extra connection per node.

## Tables

On startup each primitive creates its tables if they don't exist:

| Primitive | Tables |
|---|---|
| Atom | `substrate_atom` |
| Journal | `substrate_journal`, `substrate_journal_entries` |
| Mailbox | `substrate_mailbox` |

To manage the schema yourself, set `substrate.postgresql.<primitive>.auto-create-schema` to
`false` and apply the scripts shipped in the jar under `db/substrate/postgresql/`
(`V1__create_atom.sql`, `V1__create_journal.sql`, `V1__create_mailbox.sql`).

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
| `substrate.postgresql.atom.auto-create-schema` | `true` | Create the atom table at startup. |
| `substrate.postgresql.journal.enabled` | `true` | Serve Journal from PostgreSQL. |
| `substrate.postgresql.journal.prefix` | `substrate:journal:` | Prefix of the stored key. |
| `substrate.postgresql.journal.max-len` | `100000` | Approximate maximum entries per journal. Trimming runs every 100 appends per node, so a journal can exceed it for a while. |
| `substrate.postgresql.journal.auto-create-schema` | `true` | Create the journal tables at startup. |
| `substrate.postgresql.mailbox.enabled` | `true` | Serve Mailbox from PostgreSQL. |
| `substrate.postgresql.mailbox.prefix` | `substrate:mailbox:` | Prefix of the stored key. |
| `substrate.postgresql.mailbox.auto-create-schema` | `true` | Create the mailbox table at startup. |
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
