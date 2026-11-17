# etcd

`substrate-etcd` stores Atom in etcd. Atoms map naturally onto etcd: a key, a lease for the TTL,
and transactions for compare-and-set. It provides nothing else, so it is used alongside another
module for Journal, Mailbox and notifications (see [Combining modules](index.md#combining-modules)).

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-etcd</artifactId>
</dependency>
```

## What it needs

- **A jetcd `io.etcd.jetcd.Client` bean.** Spring Boot does not create one, and the module only
  activates when the bean exists:

    ```java
    @Bean(destroyMethod = "close")
    Client etcdClient() {
      return Client.builder().endpoints("http://localhost:2379").build();
    }
    ```

## How atoms are stored

Each atom is one key whose value holds the staleness token and the payload. Every write grants a
new lease with the requested TTL and attaches the key to it, so etcd deletes the key when the lease
expires; the previous lease is then revoked. `touch` moves the key to a fresh lease without
changing the value. Create and compare-and-set are etcd transactions, so they are atomic.

In the rare case of two writers interleaving, an old lease can be left to expire on its own
rather than being revoked; it never outlives its TTL.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.etcd.atom.enabled` | `true` | Serve Atom from etcd. |
| `substrate.etcd.atom.prefix` | `substrate:atom:` | Key prefix for atoms. |

## Local development

```bash
docker run -p 2379:2379 quay.io/coreos/etcd:v3.5.17 \
  etcd --listen-client-urls http://0.0.0.0:2379 --advertise-client-urls http://localhost:2379
```
