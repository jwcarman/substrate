# NATS

`substrate-nats` stores all three primitives in NATS JetStream and carries notifications on a
core NATS subject. It suits applications that already run NATS with JetStream.

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-nats</artifactId>
</dependency>
```

## What it needs

- **An `io.nats.client.Connection` bean.** Spring Boot does not create one; define it yourself:

    ```java
    @Bean(destroyMethod = "close")
    Connection natsConnection() throws IOException, InterruptedException {
      return Nats.connect("nats://localhost:4222");
    }
    ```

- **JetStream enabled on the server** (`nats-server -js`), with permission to create key-value
  buckets and a stream.

## How it stores each primitive

| Primitive | Storage |
|---|---|
| Atom | Keys in the key-value bucket `substrate-atoms`. Compare-and-set uses the key's revision. |
| Journal | One JetStream stream, `substrate-journal`, shared by every journal; each journal is a subject under `substrate.journal.`. Completion is recorded in the bucket `substrate-journal-completed`. |
| Mailbox | Keys in the key-value bucket `substrate-mailbox`. |
| Notifier | The core NATS subject `substrate.notifications`. |

The buckets and the stream are created at startup if they don't exist. In journal subjects,
colons become dots. In key-value keys, both colons and dots become dashes, so atom or mailbox
names that differ only by `:`, `.` or `-` share a key.

## TTLs are per bucket, not per call

!!! warning "The TTL you pass is not used"
    NATS key-value buckets have one maximum age for every key. Atoms and mailboxes on this backend
    live for their bucket's age — `substrate.nats.atom.default-ttl` and
    `substrate.nats.mailbox.default-ttl`, both 5 minutes by default — no matter what TTL `create`,
    `set` or `touch` is given. Every write and every `touch` starts the age again.

    Set the bucket age to the lifetime you need. The bucket is created with that age the first
    time; changing the property later does not change an existing bucket — update or recreate the
    bucket with the NATS CLI.

The Journal is limited the same way: retention is set for the whole stream, by
`substrate.nats.journal.max-age` (24 hours) and `substrate.nats.journal.max-messages` (100,000
messages across all journals together). Per-entry and retention TTLs are ignored, and the rest of
the Journal lifecycle is partly enforced; see
[How backends differ](../primitives/journal.md#how-backends-differ). Deleting a journal purges its
entries but leaves its completion record in place, so subscribers to a deleted completed journal
end with `Completed`, and a new journal created under the same name is complete from the start.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.nats.atom.enabled` | `true` | Serve Atom from NATS. |
| `substrate.nats.atom.prefix` | `substrate:atom:` | Key prefix for atoms. |
| `substrate.nats.atom.bucket-name` | `substrate-atoms` | The key-value bucket for atoms. |
| `substrate.nats.atom.default-ttl` | `5m` | The atom bucket's maximum age — the lifetime of every atom. |
| `substrate.nats.journal.enabled` | `true` | Serve Journal from NATS. |
| `substrate.nats.journal.prefix` | `substrate:journal:` | Key prefix for journals. |
| `substrate.nats.journal.stream-name` | `substrate-journal` | The stream holding every journal. |
| `substrate.nats.journal.max-age` | `24h` | The stream's maximum message age. |
| `substrate.nats.journal.max-messages` | `100000` | The stream's maximum message count, for all journals together. |
| `substrate.nats.journal.fetch-timeout` | `50ms` | How long a read waits for each message from a pull consumer. |
| `substrate.nats.journal.tail-batch-size` | `100` | Messages requested per pull when reading. |
| `substrate.nats.mailbox.enabled` | `true` | Serve Mailbox from NATS. |
| `substrate.nats.mailbox.prefix` | `substrate:mailbox:` | Key prefix for mailboxes. |
| `substrate.nats.mailbox.bucket-name` | `substrate-mailbox` | The key-value bucket for mailboxes. |
| `substrate.nats.mailbox.default-ttl` | `5m` | The mailbox bucket's maximum age — the lifetime of every mailbox. |
| `substrate.nats.notifier.enabled` | `true` | Use a NATS subject as the notifier. |
| `substrate.nats.notifier.subject` | `substrate.notifications` | The subject. |

## Behaviour to know

- **Journal reads create short-lived consumers.** Each read creates a pull consumer filtered to
  the journal's subject and removes it afterwards; `subscribeLast` reads every message of the
  journal to find the last few. Prefer modest journal sizes.
- **Core NATS notifications are at-most-once.** A node that is disconnected misses them; its
  subscriptions catch up on their next once-a-second read.

## Local development

```bash
docker run -p 4222:4222 nats:latest -js
```
