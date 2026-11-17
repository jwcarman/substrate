---
hide:
  - navigation
  - toc
---

<div class="jw-hero" markdown>

# Substrate

<p class="jw-hero__tagline">Distributed primitives for Java, on the infrastructure you already run.</p>

Java 25+ · Spring Boot 4.x

<div class="jw-strata" aria-hidden="true"><span></span><span></span><span></span><span></span></div>

<p class="jw-hero__actions">
  <a class="md-button md-button--primary" href="guides/getting-started/">Get started</a>
  <a class="md-button" href="https://github.com/jwcarman/substrate">View on GitHub</a>
</p>

</div>

Substrate gives a clustered application three small distributed data structures. They behave
the same whether they are stored in Redis, PostgreSQL, NATS, DynamoDB or any of the other
supported backends. Your code depends on the primitive; the backend is a dependency you add.

## The primitives

[**Atom**](primitives/atom.md)
:   A distributed `AtomicReference` with a lease. One current value, readable and writable from
    any node, with compare-and-set and change notification. It expires unless something renews it.
    Use it for sessions, presence, leader state, or any "current value" several nodes share.

[**Journal**](primitives/journal.md)
:   An ordered, append-only stream that can be replayed. Producers append; consumers tail it from
    now, from the last *N* entries, or from a checkpoint ID they saved. Use it for event feeds,
    progress updates and resumable streams such as Server-Sent Events.

[**Mailbox**](primitives/mailbox.md)
:   A distributed `CompletableFuture`. One side creates it and waits; any node delivers exactly
    one value. Use it for request and response across nodes, such as a reply that arrives on a
    different instance from the one holding the waiting request.

All three share one subscription model: block on `next(Duration)` from a virtual thread, or hand
over a callback. Every outcome — a value, a timeout, completion, expiry, deletion, an error or
cancellation — arrives as one case of a sealed `NextResult` type. The [Concepts](concepts/index.md)
page explains the model once for all three.

## Backends

Each backend is one module. Add it to the classpath and Spring Boot registers whatever it
supports; any primitive no backend supports falls back to an in-memory implementation and logs a
warning. These backends support all three primitives:

| Backend | Atom | Journal | Mailbox |
|---|:---:|:---:|:---:|
| [Redis](backends/redis.md) | ✓ | ✓ | ✓ |
| [PostgreSQL](backends/postgresql.md) | ✓ | ✓ | ✓ |
| [Hazelcast](backends/hazelcast.md) | ✓ | ✓ | ✓ |
| [NATS](backends/nats.md) | ✓ | ✓ | ✓ |
| [MongoDB](backends/mongodb.md) | ✓ | ✓ | ✓ |
| [DynamoDB](backends/dynamodb.md) | ✓ | ✓ | ✓ |

[Choosing a Backend](backends/index.md) has the full matrix, including the backends that cover
only some primitives, and how to combine them.

## Where next

- [Getting Started](guides/getting-started.md) — the dependencies and a first Atom, Journal and
  Mailbox.
- [Concepts](concepts/index.md) — subscriptions, lifecycles, TTLs and how nodes wake each other.
- [Encryption at Rest](guides/encryption.md) — AES-GCM for every stored payload with
  `substrate-crypto`.
- [Configuration](reference/configuration.md) — every `substrate.*` property.
