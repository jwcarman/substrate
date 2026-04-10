# Per-backend module consolidation

**Depends on: spec 019 (intentionally-leased cleanup) must be completed
first.** Spec 019 moves SPI interfaces into their final
`org.jwcarman.substrate.core.*` locations and removes breaking API
signatures. This spec restructures the *backend* modules to match the
new "intentionally leased" world.

## What to build

Consolidate the 24 existing per-primitive backend modules
(`substrate-{journal,mailbox,notifier}-{redis,hazelcast,postgresql,...}`)
into 8 per-backend modules (`substrate-{redis,hazelcast,postgresql,...}`),
one per infrastructure technology, each offering the primitives that
technology supports. Within each per-backend module, primitives live in
per-primitive subpackages.

Users who previously depended on `substrate-mailbox-redis` will instead
depend on `substrate-redis`, and will disable unwanted primitives via
`@ConditionalOnProperty` switches (`substrate.redis.notifier.enabled=false`
etc.) if they only want a subset.

This is a breaking change to Maven coordinates and packages. Project
is pre-release; now is the right moment.

## Module consolidation map

| Old artifacts                                                     | New artifact         | Primitives offered        |
|---|---|---|
| `substrate-journal-redis`, `substrate-mailbox-redis`, `substrate-notifier-redis` | `substrate-redis`    | Journal, Mailbox, Notifier, Atom |
| `substrate-journal-hazelcast`, `substrate-mailbox-hazelcast`, `substrate-notifier-hazelcast` | `substrate-hazelcast` | Journal, Mailbox, Notifier, Atom |
| `substrate-journal-postgresql`, `substrate-mailbox-postgresql`, `substrate-notifier-postgresql` | `substrate-postgresql` | Journal, Mailbox, Notifier, Atom |
| `substrate-journal-nats`, `substrate-mailbox-nats`, `substrate-notifier-nats` | `substrate-nats`     | Journal, Mailbox, Notifier, Atom |
| `substrate-journal-mongodb`, `substrate-mailbox-mongodb`          | `substrate-mongodb`  | Journal, Mailbox (+ Atom if feasible) |
| `substrate-journal-dynamodb`, `substrate-mailbox-dynamodb`        | `substrate-dynamodb` | Journal, Mailbox, Atom    |
| `substrate-journal-rabbitmq`, `substrate-notifier-rabbitmq`       | `substrate-rabbitmq` | Journal, Notifier         |
| `substrate-journal-cassandra`                                     | `substrate-cassandra`| Journal (only)            |
| `substrate-notifier-sns`                                          | `substrate-sns`      | Notifier (only)           |

Backends that currently only support one primitive (Cassandra → Journal
only; SNS → Notifier only) still get their own consolidated module for
consistency, even though there's no consolidation happening for them.
The rename clarifies the naming convention.

**Atom implementations** are not guaranteed in the initial cut of this
spec. Each backend module should declare the `AtomSpi` implementation
as a separate acceptance criterion only if that backend can naturally
support the atomic set-if-not-exists contract (Redis SET NX,
DynamoDB conditional write, Postgres ON CONFLICT, Mongo insertOne with
unique index, Hazelcast IMap.putIfAbsent, Cassandra LWT, NATS KV).
Backends where atomic create is awkward may leave `AtomSpi` as a
follow-on spec.

## Per-backend package reorganization

Current backend package convention:
`org.jwcarman.substrate.{journal,mailbox,notifier}.<backend>.*`

New convention:
`org.jwcarman.substrate.<backend>.{journal,mailbox,notifier,atom}.*`

Examples:

| Old package                                                | New package                                            |
|---|---|
| `org.jwcarman.substrate.notifier.redis.RedisNotifier`      | `org.jwcarman.substrate.redis.notifier.RedisNotifierSpi` *(also renamed per spec 019)* |
| `org.jwcarman.substrate.mailbox.redis.RedisMailboxSpi`     | `org.jwcarman.substrate.redis.mailbox.RedisMailboxSpi` |
| `org.jwcarman.substrate.journal.redis.RedisJournalSpi`     | `org.jwcarman.substrate.redis.journal.RedisJournalSpi` |
| `org.jwcarman.substrate.notifier.hazelcast.HazelcastNotifier` | `org.jwcarman.substrate.hazelcast.notifier.HazelcastNotifierSpi` |

A shared top-level package per backend (e.g.,
`org.jwcarman.substrate.redis`) holds cross-primitive concerns like
connection configuration and shared client factories:

```
org.jwcarman.substrate.redis/
  RedisProperties.java              # shared connection config
  RedisConnectionFactory.java       # shared client builder
  RedisAutoConfiguration.java       # marker / imports
  notifier/
    RedisNotifierSpi.java
    RedisNotifierAutoConfiguration.java
  mailbox/
    RedisMailboxSpi.java
    RedisMailboxAutoConfiguration.java
  journal/
    RedisJournalSpi.java
    RedisJournalAutoConfiguration.java
  atom/
    RedisAtomSpi.java               # if supported in this spec
    RedisAtomAutoConfiguration.java
```

## Per-primitive enable/disable

Each backend's primitive autoconfiguration is gated by a dedicated
`@ConditionalOnProperty`:

```java
@AutoConfiguration
@ConditionalOnClass(RedisClient.class)   // still gated on classpath
@ConditionalOnProperty(
    prefix = "substrate.redis.notifier",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)                // default ON
public class RedisNotifierAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(NotifierSpi.class)
  public RedisNotifierSpi redisNotifier(RedisConnectionFactory factory) {
    return new RedisNotifierSpi(factory.getClient());
  }
}
```

Users disable a specific primitive via:

```yaml
substrate:
  redis:
    notifier:
      enabled: false
```

The `@ConditionalOnProperty` has `matchIfMissing = true`, so **the
default behavior is "if the backend module is on the classpath, all
its primitives are enabled."** Users who only want a subset disable
the rest explicitly.

**PRD.md update**: remove the pre-existing constraint
`"Backend auto-configurations use @ConditionalOnClass only — no
@ConditionalOnProperty"` (if spec 019 hasn't already removed it —
verify and remove if present).

## Shared connection config

Within a consolidated module, all primitives share one set of
connection properties. Example for Redis:

```java
@ConfigurationProperties(prefix = "substrate.redis")
public record RedisProperties(
    String host,
    int port,
    String username,
    String password,
    Duration commandTimeout,
    NotifierProperties notifier,
    MailboxProperties mailbox,
    JournalProperties journal,
    AtomProperties atom) {

  public record NotifierProperties(boolean enabled, String channelPrefix) {}
  public record MailboxProperties(boolean enabled, String keyPrefix) {}
  public record JournalProperties(boolean enabled, String keyPrefix, int maxLen) {}
  public record AtomProperties(boolean enabled, String keyPrefix) {}
}
```

The top-level fields (`host`, `port`, etc.) are shared across all
primitives. The nested records per primitive carry primitive-specific
config.

Each backend's `RedisConnectionFactory` (or equivalent) is a shared
bean that every primitive's SPI implementation consumes:

```java
@AutoConfiguration
@ConditionalOnClass(RedisClient.class)
@EnableConfigurationProperties(RedisProperties.class)
public class RedisAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public RedisConnectionFactory redisConnectionFactory(RedisProperties props) {
    return new RedisConnectionFactory(props);
  }
}
```

## POM and BOM updates

### Parent `pom.xml`

- Remove all 24 old backend-module entries from `<modules>`.
- Add the 8 (or however many apply) new consolidated modules.
- Ordering: after `substrate-api`, `substrate-core`, and `substrate-bom`.

### `substrate-bom/pom.xml`

- Remove all `<dependency>` entries for old backend artifacts.
- Add `<dependency>` entries for the new consolidated artifacts with
  `${project.version}`.

### Each new backend `pom.xml`

- `groupId: org.jwcarman.substrate`
- `artifactId: substrate-<backend>`
- `packaging: jar`
- Inherit from `substrate-parent`
- `<dependencies>`:
  - `substrate-api` (compile)
  - `substrate-core` (compile — this gives SPI interfaces, WARN: this
    does drag in the in-memory fallbacks as classpath dead weight;
    acceptable per the "substrate-core contains SPIs" decision in
    spec 019)
  - Backend-specific client library (e.g., `lettuce-core` for Redis,
    `com.hazelcast:hazelcast`, etc.)
  - Test scope: `spring-boot-starter-test`, `testcontainers` (specific
    module), `awaitility`
- Apache 2.0 license header on the POM

## Integration test consolidation

Each old per-primitive module had its own `*IT` integration test class
that booted a testcontainer. After consolidation, a single
testcontainer (defined once in the consolidated module's test
resources or a base test class) is shared across all primitive ITs
in that module.

Use JUnit 5's `@Testcontainers` + `@Container static` pattern so the
container starts once per module:

```java
public abstract class AbstractRedisIT {
  @Container
  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  // shared setup
}

public class RedisNotifierIT extends AbstractRedisIT { ... }
public class RedisMailboxIT extends AbstractRedisIT { ... }
public class RedisJournalIT extends AbstractRedisIT { ... }
public class RedisAtomIT extends AbstractRedisIT { ... }
```

This replaces the previous "one testcontainer per primitive per
backend" pattern, which was wasteful.

## Artifact name map for CHANGELOG

| Removed artifact                  | Replaced by              |
|---|---|
| `substrate-journal-redis`         | `substrate-redis`        |
| `substrate-mailbox-redis`         | `substrate-redis`        |
| `substrate-notifier-redis`        | `substrate-redis`        |
| `substrate-journal-hazelcast`     | `substrate-hazelcast`    |
| `substrate-mailbox-hazelcast`     | `substrate-hazelcast`    |
| `substrate-notifier-hazelcast`    | `substrate-hazelcast`    |
| `substrate-journal-postgresql`    | `substrate-postgresql`   |
| `substrate-mailbox-postgresql`    | `substrate-postgresql`   |
| `substrate-notifier-postgresql`   | `substrate-postgresql`   |
| `substrate-journal-nats`          | `substrate-nats`         |
| `substrate-mailbox-nats`          | `substrate-nats`         |
| `substrate-notifier-nats`         | `substrate-nats`         |
| `substrate-journal-mongodb`       | `substrate-mongodb`      |
| `substrate-mailbox-mongodb`       | `substrate-mongodb`      |
| `substrate-journal-dynamodb`      | `substrate-dynamodb`     |
| `substrate-mailbox-dynamodb`      | `substrate-dynamodb`     |
| `substrate-journal-rabbitmq`      | `substrate-rabbitmq`     |
| `substrate-notifier-rabbitmq`     | `substrate-rabbitmq`     |
| `substrate-journal-cassandra`     | `substrate-cassandra`    |
| `substrate-notifier-sns`          | `substrate-sns`          |

Document this mapping in the `CHANGELOG.md` under a "Breaking changes"
heading so consumers know what to search-and-replace in their own
POMs.

## Acceptance criteria

### Module structure

- [ ] The 24 old per-primitive backend modules are **deleted** from
      the repository root and removed from the parent POM's
      `<modules>` list.
- [ ] The following new consolidated modules exist at the repository
      root, each with a `pom.xml`:
      `substrate-redis`, `substrate-hazelcast`, `substrate-postgresql`,
      `substrate-nats`, `substrate-mongodb`, `substrate-dynamodb`,
      `substrate-rabbitmq`, `substrate-cassandra`, `substrate-sns`.
- [ ] Each new module's POM declares the correct `artifactId`, inherits
      from `substrate-parent`, and lists its dependencies per the
      guidance above.

### BOM

- [ ] `substrate-bom/pom.xml` has no references to any of the old
      per-primitive backend artifacts.
- [ ] `substrate-bom/pom.xml` has a `<dependency>` entry for each new
      consolidated module with `${project.version}`.

### Package layout per backend

- [ ] Within each consolidated module, classes live under
      `org.jwcarman.substrate.<backend>` with per-primitive
      subpackages (`.notifier`, `.mailbox`, `.journal`, `.atom`).
- [ ] No class in any consolidated module lives under the old package
      convention `org.jwcarman.substrate.{journal,mailbox,notifier}.<backend>`.
- [ ] Shared connection config (`<Backend>Properties`,
      `<Backend>ConnectionFactory`, top-level `<Backend>AutoConfiguration`)
      lives in the shared top-level backend package
      (`org.jwcarman.substrate.<backend>`).

### Per-primitive enable/disable

- [ ] Each primitive's autoconfiguration class is gated by
      `@ConditionalOnProperty(prefix="substrate.<backend>.<primitive>",
      name="enabled", havingValue="true", matchIfMissing=true)`.
- [ ] Setting `substrate.<backend>.<primitive>.enabled=false` in a
      test application context prevents that primitive's SPI bean
      from being created. Verified by a `@SpringBootTest` per
      consolidated module.
- [ ] The "I only want the notifier from this backend" scenario works:
      setting `substrate.redis.mailbox.enabled=false`,
      `substrate.redis.journal.enabled=false`, and
      `substrate.redis.atom.enabled=false` (if present) leaves only
      the notifier bean registered. Verified by context test.

### Shared connection config

- [ ] Each backend module has a single `<Backend>Properties` record
      at `org.jwcarman.substrate.<backend>.<Backend>Properties`.
- [ ] Each backend module has a single connection factory (or shared
      client bean) that all primitive SPI implementations consume.

### Integration tests

- [ ] Each consolidated module has one testcontainer shared across
      its primitive integration tests (shared base class or
      `@Container static` pattern).
- [ ] All previously-passing integration tests still pass after
      consolidation.
- [ ] `./mvnw verify` (not `-DskipITs`) succeeds.

### Documentation

- [ ] `PRD.md` is updated to reflect the new consolidated module
      structure. The old 24-row "Module Structure" table is replaced
      with the consolidated layout.
- [ ] `PRD.md` no longer contains the constraint
      `"Backend auto-configurations use @ConditionalOnClass only"`.
      (Spec 019 may have already removed this; verify.)
- [ ] `CHANGELOG.md` has a "Breaking changes" entry listing the old
      → new artifact mapping so consumers can update their POMs.

### Build

- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes with ITs: `./mvnw verify`
- [ ] Apache 2.0 license headers on every new and moved file.

## Implementation notes

- This spec is large and touches every backend module. Do it in
  per-backend waves: merge Redis first (most primitives, highest
  value), get it green, move to Hazelcast, and so on. Each wave is a
  self-contained chunk that can be verified in isolation.
- Consider landing each wave as its own Git commit on a feature branch
  so the history is reviewable module-by-module, even though the spec
  itself is a single unit.
- When a backend previously had separate autoconfig classes per
  primitive, don't try to merge them into a single giant
  `@AutoConfiguration`. Keep them as separate `@AutoConfiguration`
  classes in the same module — easier to reason about, and each one
  carries exactly one `@ConditionalOnProperty` gate.
- For backends that previously didn't have an Atom implementation,
  this spec does **not** require adding one. Atom support per backend
  can land as a follow-on spec (or bundled with the consolidation wave
  for backends where it's natural). The acceptance criteria only
  require atom support where the backend naturally supports atomic
  set-if-not-exists.
- `CHANGELOG.md` probably exists already (mentioned in the git log).
  Append to it, don't replace it.
- Old module directories are **deleted**, not just emptied.
  `git rm -r substrate-journal-redis` etc. This will show up as
  massive deletions in the diff — that's expected.
- The per-backend `<Backend>ConnectionFactory` might be a new class if
  the old modules each had their own connection setup. Extract and
  unify it as part of the consolidation — don't leave three divergent
  copies in three subpackages.
