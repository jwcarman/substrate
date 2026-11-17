# Hazelcast

`substrate-hazelcast` stores all three primitives in a Hazelcast cluster and carries
notifications on a Hazelcast topic. It suits applications that already embed Hazelcast or connect
to a Hazelcast cluster.

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-hazelcast</artifactId>
</dependency>
```

## What it needs

- **A `HazelcastInstance` bean** — a member or a client. Spring Boot creates one when Hazelcast
  is on the classpath and configured; or define your own.
- **A Jackson 3 `ObjectMapper` bean**, which the Journal uses to store entries. Spring Boot
  provides one when Jackson is on the classpath.

## How it stores each primitive

| Primitive | Storage |
|---|---|
| Atom | Entries in the `IMap` `substrate-atoms`, each with its own TTL. |
| Journal | A `Ringbuffer` per journal; completion is recorded in the `IMap` `substrate-journal-completed`. |
| Mailbox | Entries in the `IMap` `substrate-mailbox`, each with its own TTL. |
| Notifier | The `ITopic` `substrate-notify`. |

Data lives in the cluster's memory, so it is as durable as your Hazelcast backup configuration
makes it. Hazelcast expires map entries itself.

## Journals and ring buffers

Each journal is a ring buffer with a fixed capacity. Substrate registers a ring-buffer
configuration for `substrate:journal:*` with:

- capacity `substrate.hazelcast.journal.ringbuffer-capacity` (100,000) — when full, the oldest
  entries are overwritten;
- time-to-live `substrate.hazelcast.journal.ringbuffer-ttl` (1 hour) — entries expire this long
  after they are written.

These replace the per-entry TTL, which this backend ignores. The configuration matches the default
journal prefix only; if you change `substrate.hazelcast.journal.prefix`, configure ring buffers
for your prefix in Hazelcast yourself.

The rest of the Journal lifecycle is partly enforced: `create` does not detect an existing
journal, the inactivity TTL is not enforced, `append` after `complete` is accepted, and the
retention TTL only clears the completed flag — the entries stay until the ring buffer's own TTL
removes them. See [How backends differ](../primitives/journal.md#how-backends-differ).

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.hazelcast.atom.enabled` | `true` | Serve Atom from Hazelcast. |
| `substrate.hazelcast.atom.prefix` | `substrate:atom:` | Key prefix for atoms. |
| `substrate.hazelcast.atom.map-name` | `substrate-atoms` | The map holding atoms. |
| `substrate.hazelcast.journal.enabled` | `true` | Serve Journal from Hazelcast. |
| `substrate.hazelcast.journal.prefix` | `substrate:journal:` | Ring-buffer name prefix. |
| `substrate.hazelcast.journal.ringbuffer-capacity` | `100000` | Entries kept per journal. |
| `substrate.hazelcast.journal.ringbuffer-ttl` | `1h` | How long each entry is kept. |
| `substrate.hazelcast.mailbox.enabled` | `true` | Serve Mailbox from Hazelcast. |
| `substrate.hazelcast.mailbox.prefix` | `substrate:mailbox:` | Key prefix for mailboxes. |
| `substrate.hazelcast.mailbox.map-name` | `substrate-mailbox` | The map holding mailboxes. |
| `substrate.hazelcast.mailbox.default-ttl` | `5m` | Not used; each mailbox's TTL comes from `create`. |
| `substrate.hazelcast.notifier.enabled` | `true` | Use a Hazelcast topic as the notifier. |
| `substrate.hazelcast.notifier.topic-name` | `substrate-notify` | The topic. |

## Local development

The simplest setup is an embedded member in each application instance, which Spring Boot
creates from a `hazelcast.yaml` on the classpath. Instances on the same network find each other
and form a cluster. To use a separate cluster, run a member in Docker and configure your
application as a Hazelcast client.
