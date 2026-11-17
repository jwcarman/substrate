# Atom

An Atom is a distributed `AtomicReference` with a lease. It holds one current value under a
name. Any node can read it, overwrite it, update it with compare-and-set, and watch it change.
It lives only as long as something renews its lease.

Use an Atom for state that has one current value shared by a cluster:

- a session, a shopping cart, or a user's presence;
- the progress of a long-running job that several nodes report on;
- a leader or owner record that must expire if its holder dies.

When you need every change rather than the latest, use a [Journal](journal.md).

## Creating and connecting

```java
@Autowired AtomFactory atoms;

// Write an initial value and start a one-hour lease.
Atom<Session> session =
    atoms.create("session:" + id, Session.class, new Session(userId), Duration.ofHours(1));

// Elsewhere, get a handle to the same atom. No I/O until the first operation.
Atom<Session> same = atoms.connect("session:" + id, Session.class);
```

`create` throws `AtomAlreadyExistsException` if an atom with that name exists. On some backends
an expired atom keeps its name until its data is actually deleted:

- PostgreSQL: until the [sweeper](../guides/operating.md#sweeping) runs, up to one interval;
- MongoDB: until its TTL monitor runs, about once a minute;
- DynamoDB: until AWS deletes the item, typically within days, and never if
  [TTL is not enabled](../backends/dynamodb.md#enable-ttl-on-every-table).

Elsewhere the name is free as soon as the atom expires. A handle from
`connect` throws `AtomNotFoundException` on its first operation if none does. Generic value
types take a `TypeRef` in place of the `Class`; see
[Creating and connecting](../concepts/index.md#creating-and-connecting) and
[Generic types](../concepts/index.md#generic-types).

## Reading

```java
Snapshot<Session> snapshot = session.get();
Session value = snapshot.value();
```

`get` reads the current value straight from the backend. It returns a
[`Snapshot`](../concepts/index.md#snapshots-and-staleness-tokens): the value plus a token that
identifies the write that produced it. Keep the snapshot if you plan to write conditionally or
to subscribe from this point.

`get` throws `AtomExpiredException` if the lease has run out or the atom was deleted.

## Writing

```java
session.set(updated, Duration.ofHours(1));
```

`set` replaces the value, resets the lease to the TTL you pass, and notifies every node. It
throws `AtomExpiredException` if the atom is already dead; a dead atom cannot be revived with
`set`. Create it again instead.

Writes are last-write-wins. Two nodes that `get`, change and `set` at the same time both succeed,
and one update is lost. When the new value depends on the old one, use compare-and-set.

## Compare-and-set

`compareAndSet` writes only if nobody has written since the snapshot you read:

```java
Snapshot<Session> current = session.get();
Session next = current.value().withVisit();

while (!session.compareAndSet(current, next, Duration.ofHours(1))) {
  current = session.get();
  next = current.value().withVisit();
}
```

The comparison uses the snapshot's token, not the value. A write that stores an equal value
still changes the token, so it still makes your compare-and-set fail; you cannot miss a write
because it happened to restore the old value.

| Result | Meaning | What to do |
|---|---|---|
| `true` | Written. The lease is reset and other nodes are notified. | Done. |
| `false` | Another writer got there first. Nothing was written. | Re-read and try again. |
| `AtomExpiredException` | The atom is dead. | Stop; retrying cannot succeed. |

`compareAndSet` throws `NullPointerException` if `expected` is `null`.

## Renewing the lease

```java
boolean alive = session.touch(Duration.ofHours(1));
```

`touch` resets the lease without changing the value or the token, and does not notify
subscribers. It returns `false` instead of throwing when the atom is already dead, which makes it
the way to check liveness: a heartbeat that calls `touch` learns that its atom has gone without
handling an exception.

Each `set`, `compareAndSet` and `touch` passes its own TTL, so a lease can be lengthened or
shortened as you go. Every lease TTL must be greater than zero and at most
`substrate.atom.max-ttl` (24 hours by default), or the call throws `IllegalArgumentException`.

## Deleting

```java
session.delete();
```

`delete` removes the atom at once and notifies other nodes. Afterwards `get`, `set` and
`compareAndSet` throw `AtomExpiredException`, and `touch` returns `false`. Deleting an atom that
does not exist does nothing, so `delete` is safe to retry.

## Watching for changes

An Atom subscription delivers the current value, then each new value as it is written.

```java
try (BlockingSubscription<Snapshot<Session>> sub = session.subscribe()) {
  while (sub.isActive()) {
    switch (sub.next(Duration.ofSeconds(30))) {
      case NextResult.Value<Snapshot<Session>>(var snapshot) -> push(snapshot.value());
      case NextResult.Timeout<Snapshot<Session>> _ -> sendKeepAlive();
      case NextResult.Expired<Snapshot<Session>> _ -> closeStream("session expired");
      case NextResult.Deleted<Snapshot<Session>> _ -> closeStream("session ended");
      case NextResult.Errored<Snapshot<Session>>(var cause) -> closeStream(cause);
      case NextResult.Completed<Snapshot<Session>> _, NextResult.Cancelled<Snapshot<Session>> _ -> {}
    }
  }
}
```

Or with callbacks:

```java
Subscription sub = session.subscribe((SubscriberConfig<Snapshot<Session>> cfg) -> cfg
    .onNext(snapshot -> push(snapshot.value()))
    .onExpired(() -> closeStream("session expired"))
    .onDeleted(() -> closeStream("session ended")));
```

Three things to know:

- **Subscriptions coalesce.** You see the latest value, not every value. If the atom is written
  five times while you are busy, the next `next` returns the fifth. Use a Journal when every
  change matters.
- **Only writes wake subscribers.** `set` and a successful `compareAndSet` deliver a new snapshot;
  `touch` does not.
- **An atom never completes.** Its subscriptions end with `Expired`, `Deleted`, `Errored` or
  `Cancelled`. A subscriber that misses the deletion notification finds the atom gone on its next
  read and reports `Expired`, so treat `Expired` and `Deleted` alike unless the difference
  matters to you.

### Resuming from a snapshot

Pass a snapshot you already hold, and the subscription skips the value you have:

```java
Snapshot<Session> initial = session.get();
render(initial.value());

try (var sub = session.subscribe(initial)) {
  // The first value delivered is the first write after `initial`.
}
```

If the atom has changed since that snapshot, the first `next` returns the current value at
once; if not, it waits for the next write. Passing a `null` snapshot is the same as `subscribe()`; a bare `null` literal is ambiguous
between the overloads, so pass a variable of type `Snapshot<T>`. Every
`subscribe` overload, blocking or callback, has a form that takes a starting snapshot.

## Methods

| Method | Does |
|---|---|
| `Snapshot<T> get()` | Reads the current value and token. |
| `void set(T data, Duration ttl)` | Replaces the value and resets the lease. |
| `boolean compareAndSet(Snapshot<T> expected, T data, Duration ttl)` | Writes only if the token still matches `expected`. |
| `boolean touch(Duration ttl)` | Resets the lease; `false` if the atom is dead. |
| `void delete()` | Removes the atom; safe to repeat. |
| `BlockingSubscription<Snapshot<T>> subscribe()` | Watches from the current value. |
| `BlockingSubscription<Snapshot<T>> subscribe(Snapshot<T> lastSeen)` | Watches for writes after `lastSeen`. |
| `Subscription subscribe(Subscriber<Snapshot<T>>)` and `subscribe(Consumer<SubscriberConfig<Snapshot<T>>>)` | The callback forms, each also taking an optional `lastSeen` first. |
| `String key()` | The backend key. |

## Example: a heartbeat lease

A worker claims a job by creating an atom named after it, then keeps the claim alive. If the
worker dies, the lease runs out and another worker can claim the job.

```java
boolean claim(String jobId, String workerId) {
  try {
    atoms.create("job-owner:" + jobId, String.class, workerId, Duration.ofSeconds(30));
    return true;
  } catch (AtomAlreadyExistsException _) {
    return false; // someone else holds it
  }
}

void heartbeat(Atom<String> owner) {
  if (!owner.touch(Duration.ofSeconds(30))) {
    stopWork(); // the claim lapsed; another worker may already have it
  }
}
```

A lapsed lease is noticed on the next read or write, not the instant it expires. On PostgreSQL
and MongoDB the name stays claimed until the expired data is deleted, so another worker may wait
up to a sweep interval or a minute longer to take over. Don't use this pattern on DynamoDB: there
the name can stay claimed for days. Choose a TTL
comfortably longer than the heartbeat interval, and do not treat an atom lease as a strict
mutual-exclusion lock: clocks, pauses and network delays can leave two workers briefly believing
they hold it.
