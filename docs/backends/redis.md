# Redis

`substrate-redis` stores all three primitives in Redis and carries notifications over Redis
pub/sub. It is the most common choice when Redis is already part of the stack.

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-redis</artifactId>
</dependency>
```

## What it needs

- **Spring Data Redis with Lettuce, connected to a single Redis server.** The module uses the
  `RedisConnectionFactory` Spring Boot configures from `spring.data.redis.*`, and requires it to
  be Lettuce's. Jedis, and Lettuce in Redis Cluster mode, are not supported: startup fails with a
  `ClassCastException`.
- A Redis server; Substrate's own tests run against Redis 7 (`redis:7-alpine`).

Each primitive opens its own Lettuce connection from that factory's client, and the notifier
holds one more for its pub/sub subscription.

## How it stores each primitive

| Primitive | Storage |
|---|---|
| Atom | A hash per atom (`token`, `value`) with `EXPIRE`. Create, set and compare-and-set run as Lua scripts, so each is atomic. |
| Journal | A Redis stream per journal (`XADD`), plus a `…:completed` key once completed. |
| Mailbox | A string key per mailbox with `EX`; delivery is a Lua script that writes only if nothing has been delivered. |
| Notifier | `PUBLISH` and `SUBSCRIBE` on one channel, `substrate:notifications`. |

Expiry is native: Redis deletes expired keys itself, and Substrate's sweeper has nothing to do.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.redis.atom.enabled` | `true` | Serve Atom from Redis. |
| `substrate.redis.atom.prefix` | `substrate:atom:` | Key prefix for atoms. |
| `substrate.redis.journal.enabled` | `true` | Serve Journal from Redis. |
| `substrate.redis.journal.prefix` | `substrate:journal:` | Key prefix for journal streams. |
| `substrate.redis.journal.max-len` | `100000` | Approximate maximum entries per stream (`XADD MAXLEN ~`); older entries are trimmed. |
| `substrate.redis.journal.default-ttl` | `1h` | Used only if an entry TTL is `null`, which the Journal API never passes. |
| `substrate.redis.mailbox.enabled` | `true` | Serve Mailbox from Redis. |
| `substrate.redis.mailbox.prefix` | `substrate:mailbox:` | Key prefix for mailboxes. |
| `substrate.redis.notifier.enabled` | `true` | Use Redis pub/sub as the notifier. |
| `substrate.redis.notifier.channel` | `substrate:notifications` | The pub/sub channel. |

## Behaviour to know

- **TTLs are whole seconds.** Atom TTLs are rounded down to whole seconds, with a minimum of one.
  Mailbox TTLs are rounded down too, with no minimum: a mailbox TTL under one second fails.
- **Completed journals drain at most about 200 entries.** A subscriber further behind than that
  when a journal completes ends with `Completed` without the rest; see
  [Completing](../primitives/journal.md#completing).
- **Journal TTLs apply to the whole stream.** Redis expires keys, not stream entries. Each
  `append` sets the stream's expiry to that entry's TTL, so the journal lives until the most
  recent entry's TTL runs out, and older entries live exactly as long. An entry TTL of
  `Duration.ZERO` leaves the stream's expiry unchanged. `complete` sets the stream's expiry to the
  retention TTL.
- **The Journal lifecycle is partly enforced.** `create` does not detect an existing journal, the
  inactivity TTL is not enforced, `append` after `complete` is accepted, and a deleted or expired
  journal reads as empty. See [How backends differ](../primitives/journal.md#how-backends-differ).
- **Trimming is silent.** A subscriber resuming with `subscribeAfter` from an entry that has been
  trimmed by `max-len` receives what remains, with no indication of the gap.
- **Pub/sub is fire-and-forget.** A node that is disconnected when a notification is published
  never receives it; its subscriptions catch up on their next once-a-second read.

## Local development

```bash
docker run -p 6379:6379 redis:7-alpine
```

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```
