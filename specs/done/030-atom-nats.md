# `NatsAtomSpi` — Atom backend for NATS JetStream KV

**Depends on: spec 018 (Atom primitive) must be completed first.**

## What to build

Add an `AtomSpi` implementation to the `substrate-nats` module using
NATS JetStream's Key-Value store, with revision-based optimistic
concurrency control for atomic create and native per-key TTL
(supported in recent JetStream versions) for expiry.

## NATS JetStream KV primitives used

- **`KeyValue.create(key, value)`** — atomic create. Throws
  `JetStreamApiException` with a `KEY_EXISTS` code on collision.
  Maps to `AtomAlreadyExistsException`.
- **`KeyValue.update(key, value, expectedRevision)`** — conditional
  update with optimistic concurrency. We use this for `set` with the
  revision from a prior read.
- **`KeyValue.put(key, value)`** — unconditional put; we do not use
  this for `set` because we need the atom-exists check.
- **`KeyValue.get(key)`** — returns the current entry with its
  revision number.
- **`KeyValue.delete(key)`** — marks key as deleted (tombstone).
- **Per-key TTL via the `MessageTTL` header** — requires NATS server
  2.11+ and a KV bucket created with `allowMessageTTL: true`.

If per-key TTL is not available in the deployment's NATS version,
fall back to bucket-level TTL set at bucket creation time. The
downside of bucket-level TTL is that every atom in the bucket has
the same expiry, which doesn't match the per-call TTL contract
AtomSpi promises. **Prefer per-key TTL; only fall back to
bucket-level TTL with a WARN log at startup.**

NATS JetStream handles TTL natively. `NatsAtomSpi.sweep(int)`
inherits the no-op default.

## Bucket configuration

- **Bucket name** — configurable, default `substrate-atoms`.
- **TTL mode** — per-key preferred; bucket-level fallback with a
  configured default.
- **Storage** — file (default) or memory. Use whatever the existing
  `substrate-nats` module uses for Mailbox/Journal.
- **Max value size** — inherit default from NATS.

## Files created

```
substrate-nats/src/main/java/org/jwcarman/substrate/nats/atom/
  NatsAtomSpi.java
  NatsAtomAutoConfiguration.java

substrate-nats/src/test/java/org/jwcarman/substrate/nats/atom/
  NatsAtomSpiTest.java
  NatsAtomIT.java
```

## Files modified

- `substrate-nats/src/main/java/org/jwcarman/substrate/nats/NatsProperties.java` —
  add nested `AtomProperties(boolean enabled, String bucketName)`
  with defaults `(true, "substrate-atoms")`.
- `AutoConfiguration.imports` — register `NatsAtomAutoConfiguration`.
- `substrate-nats-defaults.properties` — document new properties.

## `NatsAtomSpi` sketch

Note: the exact NATS Java client API differs between releases. This
sketch uses the `io.nats:jnats` client patterns; adjust for the
specific version the existing module uses.

```java
package org.jwcarman.substrate.nats.atom;

import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueStatus;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.AtomRecord;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;

public class NatsAtomSpi extends AbstractAtomSpi {

  private final KeyValue kv;

  public NatsAtomSpi(KeyValue kv) {
    super("substrate:atom:");
    this.kv = kv;
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    byte[] payload = encode(value, token);
    try {
      // Per-key TTL: NATS 2.11+ supports message TTL via header
      kv.create(natsKey(key), payload);
      // If per-key TTL is supported, set it here via a follow-up
      // or at publish time with the MessageTTL header. Pseudocode:
      // kv.putWithTtl(natsKey(key), payload, ttl);
    } catch (JetStreamApiException e) {
      if (e.getApiErrorCode() == 10071 /* KEY_EXISTS */) {
        throw new AtomAlreadyExistsException(key);
      }
      throw new RuntimeException("NATS KV create failed", e);
    } catch (IOException e) {
      throw new RuntimeException("NATS KV create I/O error", e);
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    try {
      KeyValueEntry entry = kv.get(natsKey(key));
      if (entry == null || entry.getOperation() != KeyValueEntry.Operation.PUT) {
        return Optional.empty();
      }
      return Optional.of(decode(entry.getValue()));
    } catch (IOException | JetStreamApiException e) {
      throw new RuntimeException("NATS KV read failed", e);
    }
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    // Optimistic concurrency: read current revision, update with it.
    try {
      KeyValueEntry entry = kv.get(natsKey(key));
      if (entry == null || entry.getOperation() != KeyValueEntry.Operation.PUT) {
        return false;   // atom is dead
      }
      byte[] payload = encode(value, token);
      kv.update(natsKey(key), payload, entry.getRevision());
      return true;
    } catch (JetStreamApiException e) {
      if (e.getApiErrorCode() == 10071 /* wrong last sequence */) {
        return false;   // raced with another writer or expired
      }
      throw new RuntimeException("NATS KV set failed", e);
    } catch (IOException e) {
      throw new RuntimeException("NATS KV set I/O error", e);
    }
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    // NATS KV doesn't have a dedicated "update TTL" primitive.
    // Work-around: read, update with same value but fresh TTL via
    // the MessageTTL header. Uses the same optimistic concurrency
    // as set.
    try {
      KeyValueEntry entry = kv.get(natsKey(key));
      if (entry == null || entry.getOperation() != KeyValueEntry.Operation.PUT) {
        return false;
      }
      kv.update(natsKey(key), entry.getValue(), entry.getRevision());
      return true;
    } catch (JetStreamApiException | IOException e) {
      return false;
    }
  }

  @Override
  public void delete(String key) {
    try {
      kv.delete(natsKey(key));
    } catch (IOException | JetStreamApiException e) {
      throw new RuntimeException("NATS KV delete failed", e);
    }
  }

  // NATS KV keys must be alphanumeric + `-_/`. Our backend-qualified
  // keys include `:` which isn't allowed — convert.
  private static String natsKey(String substrateKey) {
    return substrateKey.replace(':', '_');
  }

  private static byte[] encode(byte[] value, String token) {
    // Prepend token as length-prefixed UTF-8
    byte[] tokenBytes = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] result = new byte[4 + tokenBytes.length + value.length];
    // write length of token (4 bytes big-endian)
    result[0] = (byte) (tokenBytes.length >>> 24);
    result[1] = (byte) (tokenBytes.length >>> 16);
    result[2] = (byte) (tokenBytes.length >>> 8);
    result[3] = (byte) tokenBytes.length;
    System.arraycopy(tokenBytes, 0, result, 4, tokenBytes.length);
    System.arraycopy(value, 0, result, 4 + tokenBytes.length, value.length);
    return result;
  }

  private static AtomRecord decode(byte[] payload) {
    int tokenLen = ((payload[0] & 0xFF) << 24)
        | ((payload[1] & 0xFF) << 16)
        | ((payload[2] & 0xFF) << 8)
        | (payload[3] & 0xFF);
    String token = new String(payload, 4, tokenLen, java.nio.charset.StandardCharsets.UTF_8);
    byte[] value = new byte[payload.length - 4 - tokenLen];
    System.arraycopy(payload, 4 + tokenLen, value, 0, value.length);
    return new AtomRecord(value, token);
  }
}
```

## Auto-configuration

```java
@AutoConfiguration
@ConditionalOnClass(KeyValue.class)
@ConditionalOnProperty(prefix = "substrate.nats.atom",
                       name = "enabled",
                       havingValue = "true",
                       matchIfMissing = true)
public class NatsAtomAutoConfiguration {

  @Bean
  @ConditionalOnBean(Connection.class)
  @ConditionalOnMissingBean(AtomSpi.class)
  public NatsAtomSpi natsAtomSpi(Connection connection, NatsProperties props) throws IOException {
    // Get or create the KV bucket
    KeyValueConfiguration config = KeyValueConfiguration.builder()
        .name(props.atom().bucketName())
        // .allowMessageTTL(true) if NATS 2.11+
        .build();
    KeyValue kv;
    try {
      kv = connection.keyValueManagement().create(config);
    } catch (JetStreamApiException e) {
      // Bucket already exists — just get it
      kv = connection.keyValue(props.atom().bucketName());
    }
    return new NatsAtomSpi(kv);
  }
}
```

## Acceptance criteria

- [ ] `NatsAtomSpi` implements all `AtomSpi` methods.
- [ ] `create` uses `kv.create(...)` and throws
      `AtomAlreadyExistsException` on the `KEY_EXISTS` error code.
- [ ] `read` returns `Optional.empty()` for absent, deleted, or
      expired entries (operation != PUT).
- [ ] `set` uses the read-revision-then-update pattern with
      `kv.update(...)` and returns `false` if the atom is dead.
- [ ] `touch` re-writes the existing value with a fresh TTL header
      (or falls back to bucket-level TTL).
- [ ] `delete` marks the key as deleted (JetStream tombstone).
- [ ] `sweep(int)` inherits the no-op from `AbstractAtomSpi`.
- [ ] The backend-qualified key `substrate:atom:<name>` is
      translated to a NATS-legal key (colons replaced).
- [ ] Value payload encodes `(token, bytes)` in a stable format
      (length-prefixed token + value bytes).
- [ ] `NatsProperties.atom` nested record with `enabled` and
      `bucketName` defaults.
- [ ] `NatsAtomAutoConfiguration` registered in
      `AutoConfiguration.imports`.
- [ ] `NatsAtomIT` uses Testcontainers NATS with JetStream enabled
      and exercises create/read/set/touch/delete, concurrent create
      collision (exactly one wins via optimistic concurrency
      failure), and TTL expiry if the test NATS version supports
      per-key TTL.
- [ ] Apache 2.0 license headers on every new file.
- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`

## Implementation notes

- **Per-key TTL requirement:** NATS JetStream per-key TTL is a
  relatively new feature (server 2.11+). Older versions only
  support bucket-level TTL. If the target deployment is on an
  older version, bucket-level TTL is a real limitation — all atoms
  in the bucket share the same expiry. Document this in the class
  javadoc and log a WARN at startup if the configured NATS version
  lacks per-key TTL.
- **Key character restrictions:** NATS KV keys are limited to
  `[a-zA-Z0-9_/\-=.]`. The substrate key prefix uses `:` which
  isn't allowed. Replace `:` with `_` (or another safe char) at
  the SPI boundary. Document this choice — if the existing NATS
  Mailbox/Journal implementations use a different substitution,
  match them.
- **Payload encoding:** we encode `(token, bytes)` as a single KV
  value because NATS KV doesn't have sub-fields. Length-prefixed
  token is simple and deterministic. An alternative is to store
  the token in a JetStream message header; that's more idiomatic
  but ties the implementation to NATS message internals. First
  cut: length-prefixed payload.
- **Optimistic concurrency for `set`:** NATS KV's `update` takes
  an expected revision number. If the revision has changed, the
  update fails. We use this to detect "atom was replaced or
  expired between my read and my write." A retry loop is possible
  but out of scope — just return `false` on conflict.
- **Existing `NatsJournalSpi` and `NatsMailboxSpi`:** look at how
  they handle Connection injection, error wrapping, and
  testcontainer setup. Follow the same patterns.
- **Value-level vs header-level TTL:** if using per-key TTL via
  message headers, be aware that every `put`/`update` needs the
  header set explicitly — it doesn't persist from a previous
  call. This is why `touch` has to re-write the value with a
  fresh header.
