# Choosing a Backend

A backend module stores the primitives' data, and most also carry the notifications that wake
subscribers on other nodes. Each module covers one technology and supplies whichever primitives
that technology suits.

## What each module provides

| Backend | Module | Atom | Journal | Mailbox | Notifier |
|---|---|:---:|:---:|:---:|:---:|
| [Redis](redis.md) | `substrate-redis` | ✓ | ✓ | ✓ | ✓ |
| [PostgreSQL](postgresql.md) | `substrate-postgresql` | ✓ | ✓ | ✓ | ✓ |
| [Hazelcast](hazelcast.md) | `substrate-hazelcast` | ✓ | ✓ | ✓ | ✓ |
| [NATS](nats.md) | `substrate-nats` | ✓ | ✓ | ✓ | ✓ |
| [MongoDB](mongodb.md) | `substrate-mongodb` | ✓ | ✓ | ✓ | — |
| [DynamoDB](dynamodb.md) | `substrate-dynamodb` | ✓ | ✓ | ✓ | — |
| [Cassandra](cassandra.md) | `substrate-cassandra` | ✓ | ✓ | — | — |
| [etcd](etcd.md) | `substrate-etcd` | ✓ | — | — | — |
| [RabbitMQ](rabbitmq.md) | `substrate-rabbitmq` | — | ✓ | — | ✓ |
| [SNS and SQS](sns.md) | `substrate-sns` | — | — | — | ✓ |
| [In-memory](in-memory.md) | `substrate-core` | ✓ | ✓ | ✓ | ✓ (one node) |

Anything no module on the classpath provides falls back to the in-memory implementation, with a
warning at startup. That applies to the notifier too.

## Choosing

**Start from the infrastructure you already run.** Substrate's value is that you don't add a new
system to get these primitives. If you have Redis, use Redis; if PostgreSQL is your only shared
infrastructure, use PostgreSQL. The four modules that provide everything — Redis, PostgreSQL,
Hazelcast and NATS — are one-dependency setups.

Then weigh what matters to the application:

- **One dependency, everything included.** Redis, PostgreSQL, Hazelcast or NATS.
- **Fast change notification across nodes.** Pick a module with a notifier, or add one (below).
  Without one, other nodes notice changes only through their once-a-second re-read.
- **Expired data cleaned up by the store itself.** Redis, Hazelcast, NATS, MongoDB, Cassandra,
  etcd and RabbitMQ expire data natively. PostgreSQL and the in-memory backend rely on
  Substrate's [sweeper](../guides/operating.md#sweeping), a scheduled delete that runs on every
  node. DynamoDB deletes expired items only once you
  [enable TTL on its tables](dynamodb.md#enable-ttl-on-every-table), and then within days.
- **Already on AWS without Redis.** DynamoDB for storage, with `substrate-sns` as the notifier.
- **A coordination store you already operate.** etcd for Atom, with another module for Journal
  and Mailbox.

Each backend's page states what it needs, how it stores each primitive, and its limits — such as
a Journal length cap, or entry TTLs a backend ignores.

## Combining modules

Modules can be mixed. A common reason is a primitive your main backend lacks:

```xml
<!-- etcd for Atom; Redis for Journal, Mailbox and notifications -->
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-etcd</artifactId>
</dependency>
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-redis</artifactId>
</dependency>
```

When two modules on the classpath both provide a primitive, which one is used is decided by
auto-configuration order, not by anything you declare — and with MongoDB's Journal or Mailbox,
both register and the application fails to start. Make the choice explicit by
switching off the one you don't want. Every module has an `enabled` property per primitive:

```yaml
substrate:
  redis:
    atom:
      enabled: false   # etcd serves Atom; Redis serves the rest
```

## Notifiers

A notifier carries short "this changed" messages between nodes so subscribers wake immediately.
[How nodes find out about changes](../concepts/index.md#how-nodes-find-out-about-changes)
explains the mechanism. The notifier needn't be the storage technology:

- **MongoDB, DynamoDB, Cassandra and etcd have no notifier.** On their own, a change made on one
  node reaches subscribers on another within about a second, through the re-read every
  subscription does. Add `substrate-sns`, `substrate-rabbitmq` or another notifier module when
  that delay matters. For `substrate-rabbitmq` as a notifier only, switch its Journal off
  ([RabbitMQ](rabbitmq.md#the-notifier)).
- **Storage with a notifier, plus a different notifier.** Switch the storage module's notifier off
  so exactly one is active:

```yaml
substrate:
  postgresql:
    notifier:
      enabled: false   # notifications go through SNS instead
```

!!! warning "Exactly one notifier"
    `substrate-sns` registers its notifier even when another module has registered one. Depending
    on auto-configuration order, both can end up in the context, and the application then fails to
    start. When you add `substrate-sns` next to
    Redis, PostgreSQL, Hazelcast, NATS or RabbitMQ, set that module's
    `substrate.<backend>.notifier.enabled` to `false`.

Notifiers are broadcasts: every node receives every notification and ignores those for
primitives it isn't watching. Their volume grows with the cluster's write rate, not with the
number of subscriptions.
