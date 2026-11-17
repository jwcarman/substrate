# Operating in Production

This page covers what Substrate does at run time — the threads and queries it creates, how it
cleans up, how it fails and how it shuts down — so you can size and watch it.

## What each subscription costs

Every open subscription, blocking or callback, has:

- one virtual thread (a *feeder*) that reads the primitive from the backend;
- for callback subscriptions, a second virtual thread that calls your `Subscriber`;
- a registration with the node's notifier.

The feeder reads when a notification arrives and, regardless, **at least once a second**. Load on
the backend therefore grows with the number of open subscriptions across the cluster, even when
nothing is being written. A thousand idle subscriptions are roughly a thousand small reads per
second. Size the backend — and, for PostgreSQL, the connection pool — for your peak number of
concurrent subscriptions.

Close subscriptions you no longer need. `BlockingSubscription` is `AutoCloseable`, so
try-with-resources does it for you; a callback `Subscription` needs an explicit `cancel()`.

Writes cost one or two backend operations each, plus one notification.

## Journal backpressure

Each Journal subscription buffers up to `substrate.journal.subscription.queue-capacity` entries
(1,024 by default) between the backend and the consumer. A consumer that falls behind fills its
buffer, and its feeder pauses until there is room. Nothing is dropped, and other subscriptions are
unaffected. Memory per subscription is bounded by the capacity times your entry size.

## TTL ceilings

Every TTL is checked against a ceiling, so a bug cannot create data that lives for a year:

```yaml
substrate:
  atom:
    max-ttl: 24h
  journal:
    max-inactivity-ttl: 24h
    max-entry-ttl: 7d
    max-retention-ttl: 30d
  mailbox:
    max-ttl: 30m
```

These are the defaults. A call over a ceiling throws `IllegalArgumentException`. Raise a ceiling
when the application legitimately needs longer; see [Upper limits](../concepts/index.md#upper-limits).

## Sweeping

Expired data must eventually be deleted from storage. Most backends do it themselves. For the
others, Substrate runs a *sweeper* per primitive on every node: a background virtual thread that
asks the backend to delete expired records in batches.

| Backend | Who deletes expired data |
|---|---|
| PostgreSQL | Substrate's sweeper. Keep it enabled. |
| In-memory | Substrate's sweeper. |
| Redis, Hazelcast, NATS, MongoDB, Cassandra, etcd, RabbitMQ | The store, natively. The sweeper runs and finds nothing to do. |
| DynamoDB | DynamoDB's TTL feature, which you must [enable on the tables](../backends/dynamodb.md#enable-ttl-on-every-table). |

Each tick, a sweeper deletes up to `batch-size` records, and keeps going while batches come back
full, up to 100 batches per tick. The first tick is delayed by a random part of the interval, so
nodes started together don't sweep in step. On PostgreSQL, concurrent sweepers on different nodes
skip each other's rows rather than blocking.

```yaml
substrate:
  atom:
    sweep:
      enabled: true
      interval: 1m
      batch-size: 1000
  journal:
    sweep:
      interval: 1m
  mailbox:
    sweep:
      interval: 1m
```

Expired data is invisible before it is swept: reads treat it as gone the moment its TTL passes.
Sweeping only reclaims space. On PostgreSQL the one visible effect is on names: an expired atom's
name stays taken until it is swept.

Sweep failures are logged as warnings and retried on the next tick.

## When the backend is unavailable

- **Writes and reads** — `set`, `append`, `deliver`, `get` and the rest — throw the exception the
  backend client raised, sometimes wrapped in an `UncheckedIOException` or
  `IllegalStateException`. Handle it as you would any failed call to that store.
- **Subscriptions** end with `Errored` (or `onError`) carrying the cause, and are then finished.
  To keep watching, open a new subscription once the backend is back. For a Journal, resume with
  `subscribeAfter` and the last ID you processed, and nothing is missed that the backend still
  holds.
- **Notifiers** reconnect where their client does. Notifications missed meanwhile don't lose
  changes: subscriptions still re-read every second.

Feeder failures are logged at `WARN` by `org.jwcarman.substrate.core.subscription.FeederSupport`,
with the primitive key, before the subscription ends.

## Errors in your callbacks

An exception thrown by a `Subscriber` — from `onNext` or any other handler — is logged at `WARN`
and swallowed; the subscription carries on. Handle errors inside your callback if they matter.

## Shutdown

When the Spring context closes, Substrate cancels every open subscription before the web server
begins its graceful shutdown. Blocked `next` calls return `Cancelled`, callback subscriptions get
`onCancelled`, and long-lived responses such as Server-Sent Events streams can finish instead of
holding shutdown open. Subscriptions together get five seconds to stop; after that, shutdown
proceeds anyway and the stragglers are logged.

A step already in progress — a backend call a feeder is making — is allowed to finish rather than
being interrupted, so database connections aren't torn down mid-query.

## Logging and monitoring

Substrate publishes no metrics of its own. What it logs:

| Logger | Level | When |
|---|---|---|
| `…core.autoconfigure.SubstrateAutoConfiguration` | `WARN` | A primitive fell back to the in-memory backend. In a cluster, treat this as a misconfiguration. |
| `…core.subscription.FeederSupport` | `WARN` | A subscription ended with an error. |
| `…core.subscription.CallbackPumpSubscription` | `WARN` | Your callback threw. |
| `…core.notifier.DefaultNotifier` | `WARN` | A notification could not be decoded or a handler failed. |
| `…core.sweep.Sweeper` | `WARN` / `DEBUG` | A sweep failed / how many records a sweep deleted. |
| `…core.lifecycle.ShutdownCoordinator` | `WARN` | Subscriptions did not stop within the shutdown budget. |

(`…` is `org.jwcarman.substrate`.) For capacity, watch your backend's own metrics — operations
per second, connections and storage — which reflect Substrate's load directly.
