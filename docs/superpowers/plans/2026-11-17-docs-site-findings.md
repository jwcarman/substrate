# Findings while writing the documentation site

Things the docs work turned up for the owner. None of them is changed in this work; the site
documents what the code does today.

## Most important

0. **Only the in-memory and PostgreSQL journals implement the Journal lifecycle.** `JournalSpi`
   requires `create` to be set-if-absent (`JournalAlreadyExistsException`), the inactivity TTL to
   expire the journal, `append` to throw `JournalCompletedException` after `complete` and
   `JournalExpiredException` after expiry, and reads to throw `JournalExpiredException` for a gone
   journal. The Redis, Hazelcast, NATS, MongoDB, DynamoDB, Cassandra and RabbitMQ journal SPIs
   implement none of that: `create` is the inherited no-op, the inactivity TTL is ignored,
   `append` after `complete` succeeds, and a deleted or expired journal reads as empty, so
   subscribers wait instead of receiving `Expired`. Entry and retention TTLs also differ (stream-wide
   expiry on Redis, ignored on Hazelcast, NATS and RabbitMQ; retention applied only to the
   completion marker on MongoDB, DynamoDB and Cassandra; NATS ignores retention entirely and leaves
   the completion marker behind after `delete`). `AbstractJournalSpiTest` exists in core but no
   backend runs a shared contract suite against its journal. The site documents each backend's
   actual behaviour in a table on the Journal page.

## README errors (fixed by the README trim, the site states the code's behaviour)

1. **`NextResult` has seven variants, not six.** `Cancelled` exists (a local `cancel()` or the
   shutdown coordinator). The README's exhaustive `switch` examples list six `case`s and no
   `default`, so they do not compile against 0.10.0. The `Journal` Javadoc's usage example has the
   same gap.
2. **The Quick Start cannot produce factories in a Spring Boot app.** It adds `codec-jackson` only.
   The factories are `@ConditionalOnBean(CodecFactory.class)`, and the `CodecFactory` bean comes
   from `codec-autoconfigure` (via `codec-spring-boot-starter`), which `substrate-core` has only in
   test scope. Without it the application starts with no `AtomFactory`, `JournalFactory` or
   `MailboxFactory`, and no error.
3. **Mailbox `create` does not throw on a name collision.** The README's "eager create" section says
   every factory throws `*AlreadyExistsException`; `MailboxFactory.create` is last-write-wins and
   there is no `MailboxAlreadyExistsException`.
4. **Mailbox "consumed" state.** The README says that once a subscriber has read the value,
   "subsequent subscriptions immediately receive `Completed`". The code reads the value from the
   backend for every subscription: each subscriber gets the value, then `Completed`, until the TTL
   elapses or the mailbox is deleted.

## Code observations

5. **Codec ordering list is partial.** `SubstrateAutoConfiguration` orders itself after the Jackson,
   Gson and Protobuf codec auto-configurations only. Jackson 2 (`Jackson2CodecAutoConfiguration`),
   JSON-B and Fory are not listed, so with one of those codecs the factories exist only if the
   alphabetical fallback happens to order codec first — the accident the comment there describes.
6. **`MailboxSpi.get` Javadoc** says it returns empty when the mailbox has expired; the
   implementations checked (in-memory, Redis) throw `MailboxExpiredException`, which is what
   `DefaultMailbox` relies on.
7. **Expired names stay taken on sweeper backends.** `PostgresAtomSpi.create` inserts with
   `ON CONFLICT (key) DO NOTHING`, so an atom that has expired but not yet been swept still makes
   `create` throw `AtomAlreadyExistsException`. Backends with native expiry free the name at once.
   Get-or-create and lease-takeover code behaves differently by backend.
8. **Two clocks in the PostgreSQL atom.** `expires_at` is written from the application's clock
   (`Instant.now().plus(ttl)`) and compared against the database's (`NOW()`). Skew between them
   shortens or lengthens every lease by the skew.
9. **Redis needs Lettuce, single-node.** `RedisAtomAutoConfiguration` (and its siblings) cast the
   `RedisConnectionFactory` to `LettuceConnectionFactory` and its native client to `RedisClient`.
   An application using Jedis, or Lettuce in cluster mode (`RedisClusterClient`), fails at startup
   with a `ClassCastException`.
10. **`substrate-sns` alongside another notifier registers two `NotifierSpi` beans.**
    `SnsNotifierAutoConfiguration` guards its bean with `@ConditionalOnMissingBean(SnsNotifierSpi.class)`
    rather than `NotifierSpi`, so with PostgreSQL, Redis, NATS, Hazelcast or RabbitMQ also on the
    classpath both notifiers register and injecting the single `NotifierSpi` in
    `SubstrateAutoConfiguration.notifier` is ambiguous. The README suggests exactly this pairing
    ("use substrate-postgresql for storage and add … substrate-sns purely for the cross-node
    wake-up"). The docs tell users to switch the other notifier off
    (`substrate.<backend>.notifier.enabled=false`).
11. **Two modules for the same primitive: the winner is decided by ordering.** Every SPI bean is
    `@ConditionalOnMissingBean(<Primitive>Spi.class)`, and the backend auto-configurations do not
    order themselves against each other, so which module serves a primitive both support depends on
    auto-configuration ordering. The docs tell users to disable the one they don't want.
12. **NATS ignores per-call TTLs for Atom and Mailbox.** `NatsAtomSpi` and `NatsMailboxSpi` never
    use the `ttl` argument; every key lives for the KV bucket's max age, set once from
    `substrate.nats.atom.default-ttl` / `substrate.nats.mailbox.default-ttl` (5 minutes) when the
    bucket is created. `atom.set(v, Duration.ofHours(1))` still expires five minutes after the last
    write; a mailbox created for 20 minutes expires after 5. Changing the property later does not
    change an existing bucket. `set` and `touch` are also read-then-put rather than conditional, so
    they can recreate an atom deleted between the two calls.
13. **DynamoDB never enables TTL on its tables.** The auto-created tables store an epoch-seconds
    `ttl` attribute but nothing calls `UpdateTimeToLive`, and the DynamoDB SPIs inherit the no-op
    sweep. Expired atoms and mailboxes are hidden by read-time checks but never deleted; journal
    entries are neither filtered on read nor deleted, so expired entries are replayed by
    `subscribeLast`/`subscribeAfter`. Tables are created with provisioned throughput of 5/5.
14. **RabbitMQ journal reads the whole stream on every poll.** `readAfter` consumes the stream from
    the start and filters in memory; `isComplete` also scans it. Each subscription polls about
    once a second, so cost grows with journal length × subscribers.
15. **Hazelcast ring-buffer settings only apply to the default prefix.** The `RingbufferConfig` is
    registered for the hard-coded pattern `substrate:journal:*`; with a custom
    `substrate.hazelcast.journal.prefix`, journals get Hazelcast's default ring-buffer config.
16. **Properties with no effect.** `substrate.{hazelcast,mongodb,dynamodb}.mailbox.default-ttl` and
    `substrate.dynamodb.journal.ttl` are bound but unused; `substrate.{redis,cassandra}.journal.default-ttl`
    apply only when the entry TTL is `null`, which `DefaultJournal` never passes.
