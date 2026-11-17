# In-Memory

The in-memory backend ships in `substrate-core`. It implements Atom, Journal, Mailbox and the
notifier inside one JVM, and needs no setup.

## When it is used

Spring Boot registers it automatically, primitive by primitive, whenever no backend module
provides that primitive. Each fallback logs a warning at startup:

```text
No Atom implementation found; using in-memory fallback (single-node only). For clustered
deployments, add a backend module (e.g. substrate-redis).
```

You might see this for one primitive only — for example, with `substrate-etcd` alone, Atom is
stored in etcd while Journal, Mailbox and the notifier fall back to memory. The warning tells you
which.

## What it does

It behaves like the other backends as far as one JVM can:

- TTLs, leases, completion and deletion work as described in [Concepts](../concepts/index.md),
  and subscriptions deliver every outcome.
- Expired records are removed by the [sweeper](../guides/operating.md#sweeping).
- A Journal keeps at most 100,000 entries; beyond that the oldest are dropped first.

## What it does not do

- **Share anything between JVMs.** Two instances of your application each have their own atoms,
  journals and mailboxes. A value delivered on one node never reaches a subscriber on another.
- **Survive a restart.** Everything is lost when the JVM stops.

Use it for local development, for tests, and for applications that run as a single instance. In
a cluster, treat the startup warning as a configuration error.

## In tests

The in-memory backend is what makes Substrate code easy to test: with no backend module on the
test classpath, the factories work with no containers. See [Testing](../guides/testing.md).
