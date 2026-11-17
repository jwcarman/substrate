# MongoDB

`substrate-mongodb` stores all three primitives in MongoDB collections. It has no notifier of its
own; see [Notifiers](index.md#notifiers).

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-mongodb</artifactId>
</dependency>
```

## What it needs

- **A `MongoTemplate` bean**, which Spring Boot creates from `spring.data.mongodb.*`
  (`substrate-mongodb` brings `spring-boot-starter-data-mongodb`).
- **Permission to create indexes** on its collections. MongoDB 7 is what Substrate's tests run
  against.

## How it stores each primitive

| Primitive | Storage |
|---|---|
| Atom | One document per atom in `substrate_atom`, with an `expireAt` date. |
| Journal | One document per entry in `substrate_journal`, indexed by journal key and entry ID, plus a marker document once completed. Entry IDs are time-ordered UUIDs (version 7). |
| Mailbox | One document per mailbox in `substrate_mailbox`, with an `expireAt` date. |

At startup each collection gets a TTL index on `expireAt`, and the journal collection a compound
index on key and entry ID.

## Expiry

MongoDB deletes expired documents itself, through its TTL monitor, which runs about once a
minute. Until it does:

- atoms and mailboxes are already treated as expired, because reads check `expireAt`;
- journal entries are not checked on read, so an expired entry can still be replayed by
  `subscribeLast` or `subscribeAfter` until the monitor removes it.

The journal's TTL index is created only when `substrate.mongodb.journal.ttl` is not zero. Each
entry's expiry comes from the TTL passed to `append`; an entry appended with `Duration.ZERO` never
expires.

The Journal lifecycle is partly enforced; the retention TTL applies only to the completion marker.
See [How backends differ](../primitives/journal.md#how-backends-differ).

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.mongodb.atom.enabled` | `true` | Serve Atom from MongoDB. |
| `substrate.mongodb.atom.prefix` | `substrate:atom:` | Key prefix for atoms. |
| `substrate.mongodb.atom.collection-name` | `substrate_atom` | The atom collection. |
| `substrate.mongodb.journal.enabled` | `true` | Serve Journal from MongoDB. |
| `substrate.mongodb.journal.prefix` | `substrate:journal:` | Key prefix for journals. |
| `substrate.mongodb.journal.collection-name` | `substrate_journal` | The journal collection. |
| `substrate.mongodb.journal.ttl` | `24h` | When zero, no TTL index is created on the journal collection. |
| `substrate.mongodb.mailbox.enabled` | `true` | Serve Mailbox from MongoDB. |
| `substrate.mongodb.mailbox.prefix` | `substrate:mailbox:` | Key prefix for mailboxes. |
| `substrate.mongodb.mailbox.collection-name` | `substrate_mailbox` | The mailbox collection. |
| `substrate.mongodb.mailbox.default-ttl` | `5m` | Not used; each mailbox's TTL comes from `create`. |

## Local development

```bash
docker run -p 27017:27017 mongo:7
```

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/app
```
