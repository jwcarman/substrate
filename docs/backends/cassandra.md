# Cassandra

`substrate-cassandra` stores Atom and Journal in Apache Cassandra. It has no Mailbox and no
notifier; add another module for those (see [Combining modules](index.md#combining-modules)).

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-cassandra</artifactId>
</dependency>
```

## What it needs

- **A `CqlSession` bean connected to a keyspace.** Spring Boot creates one from
  `spring.cassandra.*` (`substrate-cassandra` brings `spring-boot-starter-data-cassandra`). Set
  `spring.cassandra.keyspace-name`; the tables are created in that keyspace.
- **Permission to create tables**, unless you create them yourself. Cassandra 4.1 is what
  Substrate's tests run against.

## How it stores each primitive

| Primitive | Storage |
|---|---|
| Atom | One row per atom in `substrate_atoms`, written `USING TTL`. Every write is a lightweight transaction: `IF NOT EXISTS` for create, `IF EXISTS` for set and touch, a token condition for compare-and-set. |
| Journal | One row per entry in `substrate_journal`, partitioned by journal key, written `USING TTL`; a marker row once completed. |

With `auto-create-schema` (the default) the tables are created at startup if they don't exist.

## Behaviour to know

- **Expiry is native.** Cassandra drops expired rows itself.
- **TTLs are whole seconds.** Atom TTLs are rounded down to whole seconds, with a minimum of one.
- **Lightweight transactions cost more.** Every atom write is a Paxos round, several times the
  latency of a plain write. Plan for that if atoms are written often.
- **The Journal lifecycle is partly enforced.** Entry TTLs apply per row; the retention TTL
  applies only to the completion marker. See
  [How backends differ](../primitives/journal.md#how-backends-differ).

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.cassandra.atom.enabled` | `true` | Serve Atom from Cassandra. |
| `substrate.cassandra.atom.prefix` | `substrate:atom:` | Key prefix for atoms. |
| `substrate.cassandra.atom.table-name` | `substrate_atoms` | The atom table. |
| `substrate.cassandra.atom.auto-create-schema` | `true` | Create the table at startup. |
| `substrate.cassandra.journal.enabled` | `true` | Serve Journal from Cassandra. |
| `substrate.cassandra.journal.prefix` | `substrate:journal:` | Key prefix for journals. |
| `substrate.cassandra.journal.table-name` | `substrate_journal` | The journal table. |
| `substrate.cassandra.journal.auto-create-schema` | `true` | Create the table at startup. |
| `substrate.cassandra.journal.default-ttl` | `24h` | Used only if an entry or retention TTL is `null`, which the Journal API never passes. |

## Local development

```bash
docker run -p 9042:9042 cassandra:4.1
```

```yaml
spring:
  cassandra:
    contact-points: localhost:9042
    local-datacenter: datacenter1
    keyspace-name: app
```

Create the keyspace first, for example with `cqlsh`:
`CREATE KEYSPACE app WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};`
