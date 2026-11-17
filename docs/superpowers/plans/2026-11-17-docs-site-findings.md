# Findings while writing the documentation site

Things the docs work turned up for the owner. None of them is changed in this work; the site
documents what the code does today.

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
