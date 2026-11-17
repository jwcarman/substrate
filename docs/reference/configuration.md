# Configuration

Every Substrate setting is a Spring Boot property under `substrate.`. The defaults suit most
applications; nothing needs to be set to get started.

## Core

These come from `substrate-core` and apply whichever backend you use.

### TTL ceilings

| Property | Default | Meaning |
|---|---|---|
| `substrate.atom.max-ttl` | `24h` | Longest lease an atom may be given. |
| `substrate.journal.max-inactivity-ttl` | `24h` | Longest inactivity TTL a journal may be created with. |
| `substrate.journal.max-entry-ttl` | `7d` | Longest TTL an entry may be appended with. |
| `substrate.journal.max-retention-ttl` | `30d` | Longest retention a journal may be completed with. |
| `substrate.mailbox.max-ttl` | `30m` | Longest lifetime a mailbox may be created with. |

A TTL above its ceiling throws `IllegalArgumentException`. See
[Upper limits](../concepts/index.md#upper-limits).

### Journal subscriptions

| Property | Default | Meaning |
|---|---|---|
| `substrate.journal.subscription.queue-capacity` | `1024` | Entries buffered per Journal subscription before its reads pause. Must be positive. |

### Sweeping

The same three properties exist for each primitive, under `substrate.atom.sweep`,
`substrate.journal.sweep` and `substrate.mailbox.sweep`:

| Property | Default | Meaning |
|---|---|---|
| `substrate.<primitive>.sweep.enabled` | `true` | Run the sweeper for this primitive. |
| `substrate.<primitive>.sweep.interval` | `1m` | Time between sweeps. |
| `substrate.<primitive>.sweep.batch-size` | `1000` | Records deleted per batch. |

See [Sweeping](../guides/operating.md#sweeping) for which backends need it.

## Encryption

From `substrate-crypto`; see [Encryption at Rest](../guides/encryption.md).

| Property | Default | Meaning |
|---|---|---|
| `substrate.crypto.shared-key` | — | Base64 AES key (16 or 32 bytes). Setting it turns encryption on. |
| `substrate.crypto.shared-kid` | `0` | Key ID recorded with data encrypted under the shared key. |

## Backends

Each backend module has its own properties under `substrate.<backend>.`, documented with the
backend:

| Module | Properties |
|---|---|
| `substrate-redis` | [`substrate.redis.*`](../backends/redis.md#configuration) |
| `substrate-postgresql` | [`substrate.postgresql.*`](../backends/postgresql.md#configuration) |
| `substrate-hazelcast` | [`substrate.hazelcast.*`](../backends/hazelcast.md#configuration) |
| `substrate-nats` | [`substrate.nats.*`](../backends/nats.md#configuration) |
| `substrate-mongodb` | [`substrate.mongodb.*`](../backends/mongodb.md#configuration) |
| `substrate-dynamodb` | [`substrate.dynamodb.*`](../backends/dynamodb.md#configuration) |
| `substrate-cassandra` | [`substrate.cassandra.*`](../backends/cassandra.md#configuration) |
| `substrate-etcd` | [`substrate.etcd.*`](../backends/etcd.md#configuration) |
| `substrate-rabbitmq` | [`substrate.rabbitmq.*`](../backends/rabbitmq.md#configuration) |
| `substrate-sns` | [`substrate.sns.*`](../backends/sns.md#configuration) |

Two patterns hold across all of them:

- **`substrate.<backend>.<primitive>.enabled`** (default `true`) switches one primitive of one
  module off — to let another module or the in-memory fallback serve it. The notifier has the
  same switch: `substrate.<backend>.notifier.enabled`.
- **`substrate.<backend>.<primitive>.prefix`** sets the key prefix. The defaults are
  `substrate:atom:`, `substrate:journal:` and `substrate:mailbox:`. Change them to keep two
  applications sharing one store apart.

## A complete example

PostgreSQL for storage, SNS for notifications, encryption on, longer atom leases:

```yaml
substrate:
  atom:
    max-ttl: 7d
  postgresql:
    notifier:
      enabled: false
  sns:
    notifier:
      topic-arn: arn:aws:sns:us-east-1:123456789012:substrate
  crypto:
    shared-key: ${SUBSTRATE_CRYPTO_KEY}
```
