# Journal

A Journal is an ordered, append-only stream. Producers on any node append entries; consumers on
any node read them in order, either from now, from the last few entries, or from an entry they
saw earlier. A journal can be marked complete, after which it stays readable for a while and then
goes away.

Use a Journal when every change matters and order matters:

- progress or status updates for a long-running task;
- an event feed for one order, document or conversation;
- the backing store for a resumable stream, such as Server-Sent Events with `Last-Event-ID`.

When only the latest value matters, use an [Atom](atom.md).

## Creating and connecting

```java
@Autowired JournalFactory journals;

Journal<OrderEvent> events =
    journals.create("order-events:" + orderId, OrderEvent.class, Duration.ofHours(1));

// Elsewhere:
Journal<OrderEvent> same = journals.connect("order-events:" + orderId, OrderEvent.class);
```

The `Duration` given to `create` is the *inactivity TTL*: if nothing is appended for that long,
the journal expires. It must be greater than zero and at most
`substrate.journal.max-inactivity-ttl` (24 hours by default).

`create` throws `JournalAlreadyExistsException` if the name is taken; a `connect` handle throws
`JournalNotFoundException` on first use if it isn't. See
[Creating and connecting](../concepts/index.md#creating-and-connecting).

## Appending

```java
String id = events.append(new OrderEvent("shipped"), Duration.ofDays(1));
```

`append` stores the entry, resets the journal's inactivity timer, notifies subscribers on every
node, and returns the entry's ID. Within a journal, each ID is greater than the IDs of entries
appended before it, so IDs work as checkpoints. Their format depends on the backend; treat them
as opaque strings.

The `Duration` is the *entry TTL*: how long this entry is kept. `Duration.ZERO` means no TTL of
its own. It may not be negative or exceed `substrate.journal.max-entry-ttl` (7 days by default).
Backends that manage retention for the whole stream rather than per entry ignore it; their
pages say so.

Any number of nodes may append to the same journal at once. On Redis, NATS, Hazelcast and the
in-memory backend the store assigns IDs in the order it receives entries. On PostgreSQL, MongoDB,
DynamoDB, Cassandra and RabbitMQ it does not, as [Ordering with concurrent
appenders](#ordering-with-concurrent-appenders) explains.

`append` throws:

- `JournalCompletedException` once the journal has been completed;
- `JournalExpiredException` once it has expired or been deleted.

## Reading

There are three ways to subscribe. All of them deliver the entries from their starting point
onward, in ID order, then keep delivering new entries as they are appended — subject to the
[limits below](#ordering-with-concurrent-appenders).

| Method | Starts with |
|---|---|
| `subscribe()` | The next entry appended after you subscribe. Nothing already in the journal is replayed. |
| `subscribeLast(int count)` | The last `count` entries still retained, then new ones. |
| `subscribeAfter(String afterId)` | Every retained entry after `afterId`, then new ones. |

```java
try (BlockingSubscription<JournalEntry<OrderEvent>> sub = events.subscribeLast(20)) {
  while (sub.isActive()) {
    switch (sub.next(Duration.ofSeconds(15))) {
      case NextResult.Value<JournalEntry<OrderEvent>>(var entry) -> send(entry.id(), entry.data());
      case NextResult.Timeout<JournalEntry<OrderEvent>> _ -> sendKeepAlive();
      case NextResult.Completed<JournalEntry<OrderEvent>> _ -> sendDone();
      case NextResult.Expired<JournalEntry<OrderEvent>> _ -> sendGone();
      case NextResult.Deleted<JournalEntry<OrderEvent>> _ -> sendGone();
      case NextResult.Errored<JournalEntry<OrderEvent>>(var cause) -> log.error("failed", cause);
      case NextResult.Cancelled<JournalEntry<OrderEvent>> _ -> {}
    }
  }
}
```

Each entry arrives as a [`JournalEntry<T>`](../concepts/index.md#journal-entries) with its ID,
the journal's key, the payload and the time it was appended. Each method also has callback
forms taking a `Subscriber` or a `SubscriberConfig` customizer:

```java
Subscription sub = events.subscribeAfter(lastEventId, (SubscriberConfig<JournalEntry<OrderEvent>> cfg) -> cfg
    .onNext(entry -> send(entry.id(), entry.data()))
    .onCompleted(this::sendDone)
    .onExpired(this::sendGone));
```

### Resuming after a disconnect

Save the ID of the last entry you processed. To resume, pass it to `subscribeAfter`; you receive
the entries after it, then live ones. On a completed Redis journal, see the
[completion limit](#completing) below.

```java
// The client reconnects with the ID of the last event it received.
var sub = (lastEventId == null) ? events.subscribeLast(50) : events.subscribeAfter(lastEventId);
```

`subscribeAfter` can only replay what the backend still holds. Entries whose TTL has run out,
or that the backend trimmed to stay under its length limit, are gone, and the subscription does
not tell you that something was skipped. Size entry TTLs and length limits for the longest
reconnect you need to survive. Pass only IDs that this journal returned.

### Backpressure

Each subscription keeps a bounded queue of entries read from the backend but not yet taken by
`next` (`substrate.journal.subscription.queue-capacity`, 1024 by default). When a consumer falls
behind and its queue fills, that subscription stops reading from the backend until the consumer
catches up. Nothing is dropped, and other subscriptions are unaffected. A consumer that stops
calling `next` altogether holds its feeder thread until the subscription is cancelled or closed.

## Completing

```java
events.complete(Duration.ofMinutes(10));
```

`complete` closes the journal to new entries. Existing entries stay readable for the *retention
TTL* you pass, then the journal expires. `Duration.ZERO` means retain with no TTL; the maximum is
`substrate.journal.max-retention-ttl` (30 days by default).

Subscribers see completion after they have read what remains: each one receives the remaining
entries, then `Completed`. A subscription opened on an already completed journal does the same —
`subscribeLast(20)` on a finished journal delivers up to 20 entries and then `Completed`.

!!! warning "Redis: at most about 200 entries after completion"
    Redis reads a journal 100 entries at a time, and a subscription on a completed journal reads
    one more batch before it ends with `Completed`. A subscriber more than about 200 entries
    behind when the journal completes — or resuming with `subscribeAfter` that far back —
    receives those entries and `Completed`, and never the rest. On Redis, keep subscribers close
    to the head, or don't complete a journal until its consumers have caught up.

Completing is the clean way to end a stream. Consumers can tell "the producer finished"
(`Completed`) from "the journal timed out" (`Expired`) and "someone removed it" (`Deleted`).

## Deleting

```java
events.delete();
```

`delete` removes the journal and all its entries at once, in any state, and notifies subscribers,
which end with `Deleted`. A subscriber that misses the notification may instead end with
`Expired`, or on most backends keep waiting, because its next read finds nothing. Deleting a
journal that doesn't exist does nothing.

## Lifecycle at a glance

| State | `append` | Subscriptions | Ends when |
|---|---|---|---|
| Active | accepted; resets the inactivity TTL | deliver entries | `complete`, inactivity TTL, `delete` |
| Completed | throws `JournalCompletedException` | drain, then `Completed` | retention TTL, `delete` |
| Expired or deleted | throws `JournalExpiredException` | end with `Expired` or `Deleted` | — |

## How backends differ

!!! warning "The full lifecycle is enforced only by PostgreSQL and the in-memory backend"
    The behaviour described above — name collisions on `create`, the inactivity TTL, rejecting
    `append` after `complete`, and ending subscriptions with `Expired` — is the Journal contract.
    PostgreSQL and the in-memory backend implement all of it. The other backends store and replay
    entries in order and support `complete` and `delete`, but enforce less of the lifecycle. If
    your code relies on one of these behaviours, check this table for your backend.

| Backend | `create` on a taken name | Inactivity TTL | `append` after `complete` | Entry TTL | Retention TTL | Expired or deleted journal |
|---|---|---|---|---|---|---|
| In-memory, [PostgreSQL](../backends/postgresql.md) | throws | enforced | throws | per entry | enforced | `Expired` while the journal's record remains; once it is deleted or swept, reads as empty |
| [Redis](../backends/redis.md) | not checked | not enforced | accepted | resets the whole stream's expiry | expires the stream | reads as empty |
| [Hazelcast](../backends/hazelcast.md) | not checked | not enforced | accepted | ignored; ring-buffer TTL and capacity apply | clears the completed flag only | reads as empty |
| [NATS](../backends/nats.md) | not checked | not enforced | accepted | ignored; stream max age and size apply | ignored | reads as empty |
| [MongoDB](../backends/mongodb.md) | not checked | not enforced | accepted | per entry | completion marker only | reads as empty |
| [DynamoDB](../backends/dynamodb.md) | not checked | not enforced | accepted | stored but not enforced | completion marker only | reads as empty |
| [Cassandra](../backends/cassandra.md) | not checked | not enforced | accepted | per entry | completion marker only | reads as empty |
| [RabbitMQ](../backends/rabbitmq.md) | not checked | not enforced | accepted | ignored; stream max age and size apply | ignored | reads as empty |

What the differences mean in practice on the backends other than PostgreSQL and in-memory:

- A journal exists in storage only once something has been appended, so a `connect` handle used
  before the first `append` throws `JournalNotFoundException`.
- A journal never times out for inactivity. Producers that may die should `complete` or `delete`
  explicitly, and consumers should bound how long they wait.
- A subscriber on a deleted or expired journal keeps receiving `Timeout` rather than `Expired`,
  unless the deletion notification reaches it (`Deleted`). Bound such loops by time or by your
  own end-of-stream entry.
- Calling `complete` once is the producer's responsibility; nothing stops a late `append`, but
  subscribers that have already drained and completed will not see it.

## Ordering with concurrent appenders

Subscribers read entries in ID order and remember the last ID they delivered. That is exact when
IDs are assigned in the order entries become visible, which holds on Redis, NATS, Hazelcast and the
in-memory backend.

On the other backends an ID can be lower than one already visible:

- **MongoDB, DynamoDB and RabbitMQ** generate the ID on the appending node, from its clock;
  **Cassandra** uses a time-based UUID the same way.
- **PostgreSQL** takes the ID from a sequence when the entry is inserted, and two concurrent
  appends can commit in the opposite order.

With one appender per journal, which is the usual shape (one job, one producer), none of this
matters. With several nodes appending to the same journal at the same moment, a subscriber can
move past an entry that becomes visible a moment later with a lower ID, and never receive it. On
RabbitMQ it can also receive an entry twice. If you need concurrent appenders with no gaps, use
Redis, NATS or Hazelcast.

## Methods

| Method | Does |
|---|---|
| `String append(T data, Duration ttl)` | Appends an entry; returns its ID. |
| `void complete(Duration retentionTtl)` | Closes the journal to appends; keeps it readable for `retentionTtl`. |
| `void delete()` | Removes the journal and its entries; safe to repeat. |
| `BlockingSubscription<JournalEntry<T>> subscribe()` | Reads entries appended from now on. |
| `BlockingSubscription<JournalEntry<T>> subscribeLast(int count)` | Replays the last `count` entries, then reads on. |
| `BlockingSubscription<JournalEntry<T>> subscribeAfter(String afterId)` | Replays entries after `afterId`, then reads on. |
| `subscribe`, `subscribeLast`, `subscribeAfter` with a `Subscriber` or `SubscriberConfig` customizer | The callback forms. |
| `String key()` | The backend key. |

## Example: progress for a background job

The node running the job appends progress and completes the journal when it finishes. Any node
can serve a client that asks how the job is going, including after a reconnect.

```java
// The worker.
Journal<Progress> progress =
    journals.create("job:" + jobId, Progress.class, Duration.ofMinutes(15));
for (Step step : steps) {
  step.run();
  progress.append(new Progress(step.name(), step.percent()), Duration.ofHours(1));
}
progress.complete(Duration.ofMinutes(30));
```

```java
// A web node, streaming to a client.
Journal<Progress> progress = journals.connect("job:" + jobId, Progress.class);
try (var sub = lastSeen == null ? progress.subscribeLast(100) : progress.subscribeAfter(lastSeen)) {
  while (sub.isActive()) {
    if (sub.next(Duration.ofSeconds(15)) instanceof NextResult.Value<JournalEntry<Progress>>(var entry)) {
      emit(entry.id(), entry.data());
    }
  }
}
```

On PostgreSQL and the in-memory backend, the inactivity TTL also catches a worker that dies
mid-job: if no progress is appended for 15 minutes, the journal expires and every client sees
`Expired` instead of waiting forever. The other backends don't enforce the inactivity TTL (see
[How backends differ](#how-backends-differ)); there, give the client its own deadline.
