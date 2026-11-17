# Testing

Code that uses Substrate is straightforward to test, because the primitives behave the same on
every backend and one backend needs no infrastructure at all. Most tests can use the in-memory
backend; a few should run against the real one.

## Unit tests, without Spring

Build a factory over the in-memory backend directly. It needs only a codec:

```java
class PresenceServiceTest {

  private final CodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final ShutdownCoordinator shutdown = new ShutdownCoordinator();
  private final AtomFactory atoms =
      new DefaultAtomFactory(
          new InMemoryAtomSpi(),
          codecs,
          PayloadTransformer.IDENTITY,
          new DefaultNotifier(new InMemoryNotifier(), codecs),
          Duration.ofHours(24),
          shutdown);

  @AfterEach
  void cancelSubscriptions() {
    shutdown.stop();
  }

  @Test
  void joiningMarksTheUserOnline() {
    new PresenceService(atoms).join("ada");

    assertThat(atoms.connect("presence:ada", Presence.class).get().value().online()).isTrue();
  }
}
```

`DefaultJournalFactory` and `DefaultMailboxFactory` are built the same way, with
`JournalLimits.defaults()` in place of the maximum TTL for journals. Each test gets a fresh, empty
store.

## Spring Boot tests with the in-memory backend

With no backend module on the classpath, a Spring Boot test gets in-memory factories
automatically. When your application does depend on a backend module, switch its primitives off
in the test configuration and the in-memory fallback takes over:

```properties
# src/test/resources/application-test.properties
substrate.redis.atom.enabled=false
substrate.redis.journal.enabled=false
substrate.redis.mailbox.enabled=false
substrate.redis.notifier.enabled=false
```

The startup log then shows one fallback warning per primitive, which is expected here.

The in-memory backend lives inside the application context, so state carries over between test
methods that share a cached context. Use names unique to each test — a random suffix is enough —
rather than relying on a clean store.

## Testing subscriptions

Blocking subscriptions are easy to test directly; give `next` a short timeout:

```java
try (var sub = journal.subscribe()) {
  journal.append(new OrderEvent("shipped"), Duration.ofMinutes(1));

  assertThat(sub.next(Duration.ofSeconds(2)))
      .isInstanceOfSatisfying(
          NextResult.Value.class, v -> assertThat(v.value()).isNotNull());
}
```

Callback subscriptions run on their own threads, so wait for the effect with a library such as
Awaitility instead of sleeping:

```java
AtomicReference<Session> latest = new AtomicReference<>();
atom.subscribe((Snapshot<Session> snapshot) -> latest.set(snapshot.value()));
atom.set(updated, Duration.ofMinutes(1));

await().atMost(Duration.ofSeconds(2)).untilAtomic(latest, equalTo(updated));
```

Atom subscriptions coalesce, so assert on the latest value rather than on how many values
arrived; a fast `set` can replace the initial value before it is delivered.

To test expiry, use short TTLs — a second or two — and wait past them. Note that some backends
round TTLs to whole seconds.

## Integration tests against the real backend

Backends differ in ways the in-memory backend can't show you — the
[Journal lifecycle](../primitives/journal.md#how-backends-differ), TTL precision, setup such as
tables and buckets. Run at least the tests that depend on those behaviours against the backend you
deploy, using Testcontainers. Spring Boot's `@ServiceConnection` wires the container into the
connection properties the backend module uses:

```java
@SpringBootTest
@Testcontainers
class OrderEventsIT {

  @Container
  @ServiceConnection(name = "redis")
  static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Autowired JournalFactory journals;

  @Test
  void replaysAfterACheckpoint() {
    // ...
  }
}
```

For backends whose client Spring Boot doesn't configure — NATS, DynamoDB, etcd — define the
client bean in a test configuration that points at the container. Each
[backend page](../backends/index.md) names the image Substrate's own tests use.
