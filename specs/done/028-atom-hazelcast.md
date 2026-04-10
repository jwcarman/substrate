# `HazelcastAtomSpi` — Atom backend for Hazelcast

**Depends on: spec 018 (Atom primitive) must be completed first.**

## What to build

Add an `AtomSpi` implementation to the `substrate-hazelcast` module
using Hazelcast's `IMap` with per-entry TTL and `putIfAbsent` for
atomic create.

## Hazelcast primitives used

- **`IMap.putIfAbsent(key, value, ttl, TimeUnit)`** — atomic
  set-if-not-exists with per-entry TTL. Returns `null` on success
  (absent), or the previous value if the key was already present.
- **`IMap.put(key, value, ttl, TimeUnit)`** — unconditional put with
  TTL, used for `set`.
- **`IMap.replace(key, value)`** + TTL update — conditional update
  that only succeeds if the key exists.
- **`IMap.get(key)`** — returns `null` for absent or expired keys.
- **`IMap.setTtl(key, ttl, TimeUnit)`** — updates TTL without changing
  value, used for `touch`. Returns `true` if successful, `false` if
  the entry doesn't exist.
- **`IMap.delete(key)`** — idempotent removal.

Hazelcast handles TTL natively per IMap entry. `HazelcastAtomSpi.sweep(int)`
inherits the `return 0` no-op from `AbstractAtomSpi`.

## Data model

A single `IMap<String, AtomEntry>` where `AtomEntry` is a simple
Serializable holder for `(byte[] value, String token)`. The map's
entry TTL carries the expiry.

```java
public record AtomEntry(byte[] value, String token) implements Serializable {
  private static final long serialVersionUID = 1L;
}
```

IMap name is configurable; default `substrate-atoms`.

## Files created

```
substrate-hazelcast/src/main/java/org/jwcarman/substrate/hazelcast/atom/
  HazelcastAtomSpi.java
  HazelcastAtomAutoConfiguration.java
  AtomEntry.java

substrate-hazelcast/src/test/java/org/jwcarman/substrate/hazelcast/atom/
  HazelcastAtomSpiTest.java
  HazelcastAtomIT.java
```

## Files modified

- `substrate-hazelcast/src/main/java/org/jwcarman/substrate/hazelcast/HazelcastProperties.java` —
  add nested `AtomProperties(boolean enabled, String mapName)` with
  defaults `(true, "substrate-atoms")`.
- `AutoConfiguration.imports` — register
  `HazelcastAtomAutoConfiguration`.
- `substrate-hazelcast-defaults.properties` — document new
  properties.

## `HazelcastAtomSpi` sketch

```java
package org.jwcarman.substrate.hazelcast.atom;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.AtomRecord;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;

public class HazelcastAtomSpi extends AbstractAtomSpi {

  private final IMap<String, AtomEntry> map;

  public HazelcastAtomSpi(HazelcastInstance hz, String mapName) {
    super("substrate:atom:");
    this.map = hz.getMap(mapName);
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    AtomEntry previous = map.putIfAbsent(
        key, new AtomEntry(value, token), ttl.toMillis(), TimeUnit.MILLISECONDS);
    if (previous != null) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    AtomEntry entry = map.get(key);
    if (entry == null) return Optional.empty();
    return Optional.of(new AtomRecord(entry.value(), entry.token()));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    // Atomic "replace only if exists" with TTL update.
    // IMap.replace(key, value) returns null if absent.
    AtomEntry previous = map.replace(key, new AtomEntry(value, token));
    if (previous == null) {
      return false;   // atom was dead
    }
    // Update TTL after the replace.
    map.setTtl(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
    return true;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    return map.setTtl(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void delete(String key) {
    map.delete(key);
  }
}
```

**Note on `set` atomicity:** `IMap.replace` + `setTtl` is two
operations. Between the replace and the setTtl, the entry has the
*old* TTL briefly. In the worst case (extremely short TTLs), the
entry could expire between these two calls, leaving the new value
with a zero TTL. Mitigations:

- For the normal case (TTLs in seconds or longer), this race is
  irrelevant.
- For paranoid implementations: wrap both calls in a
  `PartitionAware` entry processor that does the check-and-set
  atomically. Hazelcast entry processors run on the partition owner
  with exclusive access, guaranteeing atomicity.
- Or accept the race as documented. The callers are already
  aware of the `boolean set` semantics ("returns true if the atom
  was alive and was updated") and the race window is microseconds.

Recommendation: start with the simple `replace` + `setTtl` approach.
If testing reveals the race is observable, migrate to an entry
processor.

## `AtomEntry` record

```java
package org.jwcarman.substrate.hazelcast.atom;

import java.io.Serializable;

public record AtomEntry(byte[] value, String token) implements Serializable {
  private static final long serialVersionUID = 1L;
}
```

Hazelcast serialization: Java records implement `Serializable`
natively, but register the class with Hazelcast's `SerializationConfig`
for efficient binary encoding. If the existing `substrate-hazelcast`
module uses a custom serialization strategy (Portable, Compact,
IdentifiedDataSerializable), follow the same pattern for `AtomEntry`.
Plain Java serialization works but is slower.

## Auto-configuration

```java
@AutoConfiguration
@ConditionalOnClass(HazelcastInstance.class)
@ConditionalOnProperty(prefix = "substrate.hazelcast.atom",
                       name = "enabled",
                       havingValue = "true",
                       matchIfMissing = true)
public class HazelcastAtomAutoConfiguration {

  @Bean
  @ConditionalOnBean(HazelcastInstance.class)
  @ConditionalOnMissingBean(AtomSpi.class)
  public HazelcastAtomSpi hazelcastAtomSpi(HazelcastInstance hz, HazelcastProperties props) {
    return new HazelcastAtomSpi(hz, props.atom().mapName());
  }
}
```

## Acceptance criteria

- [ ] `HazelcastAtomSpi` implements all `AtomSpi` methods.
- [ ] `create` uses `IMap.putIfAbsent` with TTL and throws
      `AtomAlreadyExistsException` if the key was already present.
- [ ] `set` uses `IMap.replace` and returns `false` if the atom is
      absent.
- [ ] `touch` uses `IMap.setTtl` and returns its result directly.
- [ ] `read` returns `Optional.empty()` for expired/absent keys.
- [ ] `delete` is idempotent.
- [ ] TTL is passed as milliseconds to Hazelcast APIs.
- [ ] `sweep(int)` inherits the no-op from `AbstractAtomSpi`.
- [ ] `HazelcastProperties.atom` nested record exists with `enabled`
      and `mapName` defaults.
- [ ] `HazelcastAtomAutoConfiguration` registered in
      `AutoConfiguration.imports`.
- [ ] `HazelcastAtomIT` uses Testcontainers Hazelcast to exercise
      create/read/set/touch/delete, concurrent create collision,
      TTL expiry (Awaitility), and touch renewal.
- [ ] Apache 2.0 license headers on every new file.
- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`

## Implementation notes

- Hazelcast's `IMap.get` transparently handles expired entries —
  they return `null` just like absent entries. No application-level
  TTL check needed.
- `IMap.setTtl` returns `false` both for "key doesn't exist" and
  "key exists but has already expired." Both cases mean the atom is
  dead, so we return `false` from `touch` without distinguishing.
- The existing `HazelcastMailboxSpi` in the consolidated module
  likely uses `IMap` with TTL already. Follow its patterns for
  serialization, error handling, and configuration.
- Java record serialization: records are `Serializable` by default
  but the auto-generated `writeObject` is slow. For performance,
  implement `IdentifiedDataSerializable` or register with Compact
  serialization. First cut can use plain Java serialization; IT
  will tell you if it matters.
- For the concurrent-create test: launch N virtual threads, all
  calling `create` on the same key, assert exactly one succeeds and
  N-1 throw `AtomAlreadyExistsException`. Use Awaitility to wait
  for all threads to complete.
