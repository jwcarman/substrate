# `RedisAtomSpi` — Atom backend for Redis

**Depends on: spec 018 (Atom primitive) must be completed first.**

## What to build

Add an `AtomSpi` implementation to the `substrate-redis` module using
Redis's `SET NX` for atomic create and native `EXPIRE` for TTL.

Redis is the cleanest backend for Atom — every AtomSpi operation maps
almost 1:1 onto a native Redis command. This spec is the smallest of
the backend-Atom specs.

## Redis primitives used

- **`SET key value EX <seconds> NX`** — atomic create with TTL.
  Returns `OK` on success, nil on collision (key already existed).
- **`SET key value EX <seconds> XX`** — conditional set with TTL,
  only succeeds if the key already exists. Used for `set`.
- **`EXPIRE key <seconds> XX`** — update TTL only if the key exists
  (Redis 7.0+). Used for `touch`. Returns 1 on success, 0 if the
  key doesn't exist.
- **`GET key`** — standard read; returns nil for absent/expired keys.
- **`DEL key`** — idempotent removal.

Since Redis only supports one `SET`-with-TTL at a time per key and
we need to store two pieces of data per atom (`value` and `token`),
we use a **Redis hash** with HSET + EXPIRE, or **two keys** with a
shared TTL, or **a single string** with the token embedded in the
value bytes.

**Chosen approach: single Redis string, with the token embedded as
a length-prefixed header in front of the codec-encoded bytes.**
Rationale:

- Atomic single-key operations — no multi-key consistency concerns
- TTL applies to the whole thing via `SET ... EX`
- `SET NX` directly gives us atomic create
- Simplest to reason about

The payload encoding matches the NATS backend's pattern (length-
prefixed token + value bytes) for consistency.

Redis handles TTL natively. `RedisAtomSpi.sweep(int)` inherits the
no-op default from `AbstractAtomSpi`.

## Files created

```
substrate-redis/src/main/java/org/jwcarman/substrate/redis/atom/
  RedisAtomSpi.java
  RedisAtomAutoConfiguration.java
  AtomPayload.java              (encode/decode helper)

substrate-redis/src/test/java/org/jwcarman/substrate/redis/atom/
  RedisAtomSpiTest.java
  RedisAtomIT.java
```

## Files modified

- `substrate-redis/src/main/java/org/jwcarman/substrate/redis/RedisProperties.java` —
  add nested `AtomProperties(boolean enabled, String keyPrefix)`
  with defaults `(true, "substrate:atom:")`.
- `AutoConfiguration.imports` — register `RedisAtomAutoConfiguration`.
- `substrate-redis-defaults.properties` — document new properties.

## Payload encoding

```java
package org.jwcarman.substrate.redis.atom;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.jwcarman.substrate.core.atom.AtomRecord;

/**
 * Length-prefixed token + raw value bytes. A single Redis key holds
 * a single atom, so we pack both the staleness token and the
 * codec-encoded value into one Redis string.
 *
 * Format:
 *   [4 bytes: token length big-endian] [token bytes UTF-8] [value bytes]
 */
final class AtomPayload {

  private AtomPayload() {}

  static byte[] encode(byte[] value, String token) {
    byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buf = ByteBuffer.allocate(4 + tokenBytes.length + value.length);
    buf.putInt(tokenBytes.length);
    buf.put(tokenBytes);
    buf.put(value);
    return buf.array();
  }

  static AtomRecord decode(byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload);
    int tokenLen = buf.getInt();
    byte[] tokenBytes = new byte[tokenLen];
    buf.get(tokenBytes);
    byte[] valueBytes = new byte[buf.remaining()];
    buf.get(valueBytes);
    return new AtomRecord(valueBytes, new String(tokenBytes, StandardCharsets.UTF_8));
  }
}
```

## `RedisAtomSpi` sketch

Using Lettuce (`io.lettuce.core`), which is the Spring Boot default
Redis client. Adapt to Jedis if the existing `substrate-redis` module
uses that instead.

```java
package org.jwcarman.substrate.redis.atom;

import io.lettuce.core.ExpireArgs;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.AtomRecord;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;

public class RedisAtomSpi extends AbstractAtomSpi {

  private final RedisCommands<byte[], byte[]> commands;

  public RedisAtomSpi(StatefulRedisConnection<byte[], byte[]> connection, String prefix) {
    super(prefix);
    this.commands = connection.sync();
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    byte[] payload = AtomPayload.encode(value, token);
    byte[] result = commands.set(
        key.getBytes(), payload, SetArgs.Builder.nx().ex(ttl));
    // Lettuce returns "OK" on success, null on NX failure
    if (result == null) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    byte[] payload = commands.get(key.getBytes());
    if (payload == null) return Optional.empty();
    return Optional.of(AtomPayload.decode(payload));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    byte[] payload = AtomPayload.encode(value, token);
    byte[] result = commands.set(
        key.getBytes(), payload, SetArgs.Builder.xx().ex(ttl));
    return result != null;   // null means XX failed (key didn't exist)
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    // Requires Redis 7.0+ for the XX flag on EXPIRE
    return commands.expire(
        key.getBytes(), ttl.toSeconds(), ExpireArgs.Builder.xx());
  }

  @Override
  public void delete(String key) {
    commands.del(key.getBytes());
  }
}
```

## Auto-configuration

```java
@AutoConfiguration
@ConditionalOnClass(io.lettuce.core.api.StatefulRedisConnection.class)
@ConditionalOnProperty(prefix = "substrate.redis.atom",
                       name = "enabled",
                       havingValue = "true",
                       matchIfMissing = true)
public class RedisAtomAutoConfiguration {

  @Bean
  @ConditionalOnBean(StatefulRedisConnection.class)
  @ConditionalOnMissingBean(AtomSpi.class)
  public RedisAtomSpi redisAtomSpi(
      StatefulRedisConnection<byte[], byte[]> connection,
      RedisProperties props) {
    return new RedisAtomSpi(connection, props.atom().keyPrefix());
  }
}
```

## Acceptance criteria

- [ ] `RedisAtomSpi` implements all `AtomSpi` methods.
- [ ] `create` uses `SET key value EX seconds NX` and throws
      `AtomAlreadyExistsException` when the command returns null
      (NX collision).
- [ ] `set` uses `SET key value EX seconds XX` and returns `false`
      when the command returns null (key was dead).
- [ ] `touch` uses `EXPIRE key seconds XX` (Redis 7.0+) and returns
      `true`/`false` based on the command's return value.
- [ ] `read` returns `Optional.empty()` for absent or expired keys
      (Redis returns nil for both — no application-level TTL check
      needed).
- [ ] `delete` uses `DEL` and is idempotent.
- [ ] `sweep(int)` inherits the no-op from `AbstractAtomSpi`.
- [ ] The token and value are encoded into a single Redis string
      using the length-prefixed format.
- [ ] `RedisProperties.atom` nested record with `enabled` and
      `keyPrefix` defaults.
- [ ] `RedisAtomAutoConfiguration` registered in
      `AutoConfiguration.imports`.
- [ ] `RedisAtomIT` uses Testcontainers Redis (7.0+) and exercises
      create/read/set/touch/delete, concurrent create collision,
      TTL expiry (Awaitility — Redis expires keys lazily on access
      plus periodic background sampling, so Awaitility polling
      typically observes expiry within ~100-200 ms).
- [ ] A `create` followed by an immediate `read` returns the
      exact-same token and value (round-trip test).
- [ ] Apache 2.0 license headers on every new file.
- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`

## Implementation notes

- **Redis version requirement:** `EXPIRE ... XX` was added in Redis
  7.0. If the target deployment is on an older version, use
  `GETEX key EX seconds` as the touch primitive (Redis 6.2+), or
  fall back to a GET + SET XX sequence with obvious race concerns.
  The `substrate-redis` module already requires a specific Redis
  version; match that. Prefer 7.0+ for the clean `EXPIRE XX` path.
- **Byte-array codec:** use Lettuce's `ByteArrayCodec` for the
  connection so you can `SET` binary payloads directly without
  base64 encoding overhead. The existing `substrate-redis` module
  may already have a connection with this codec wired; reuse it.
- **Key prefix:** the default `substrate:atom:` prefix isolates
  substrate's atoms from other keys in the same Redis database. If
  the existing `substrate-redis` module uses different prefixes for
  other primitives, follow that convention.
- **`SetArgs.Builder.nx().ex(ttl)`** — the Lettuce builder pattern
  for the combined NX + EX flags. Make sure the TTL is passed as a
  `Duration` or converted to seconds correctly.
- **No need for Lua scripting.** All the atomic operations we need
  are single-command primitives in Redis. Avoid Lua scripts unless
  a future requirement forces it.
- **Connection reuse:** the existing `RedisJournalSpi` /
  `RedisMailboxSpi` / `RedisNotifierSpi` in the consolidated
  `substrate-redis` module likely share a `StatefulRedisConnection`.
  Reuse it for the atom SPI — don't create a separate connection.
- **Observing TTL expiry in tests:** Redis doesn't have a
  deterministic "evict now" moment. Keys expire lazily (on first
  access after expiry) plus via a background sampler. For tests
  using Awaitility, just poll `read(key)` until it returns empty —
  that combination hits both eviction paths and typically
  completes within 200 ms of actual expiry.
- **Concurrent create test:** Redis's `SET NX` is atomic at the
  single-node level and at the Redis Cluster level (for a single
  key). Launch 8 virtual threads calling `create` on the same key
  and assert exactly one succeeds.
