# Typed notifier routing: sealed Notification + DefaultNotifier + byte[] SPI

## What to build

Replace the current stringly-typed notifier protocol with a typed routing
layer. The public-facing contract becomes a sealed `Notification`
hierarchy delivered through per-primitive-type subscribe methods. The
backend `NotifierSpi` collapses to an opaque byte[] broadcast channel.
All the type-awareness, event-kind discrimination, and local fan-out
logic lives in a new `DefaultNotifier` class in `substrate-core` that
wraps the backend SPI.

### Layering (single responsibility per layer)

| Layer | Knows about | Does NOT know about |
|---|---|---|
| **Callers** (primitives, feeders, future user code) | The typed `Notifier` interface and the sealed `Notification` hierarchy delivered to handlers. | Byte encoding, wire format, `PrimitiveType` enum, `EventType` enum, `RawNotification`, any `NotifierSpi` detail. |
| **`DefaultNotifier`** (substrate-core) | Typed `Notifier` contract on one side; byte[] `NotifierSpi` on the other. Owns the `Codec<RawNotification>` and the nested routing map. Does **all** the heavy lifting — encode outbound, decode inbound, route to matching handlers. | Which backend the SPI is using. Whether there's a network underneath or just a local in-memory list. |
| **`NotifierSpi`** (backends) | A single opaque byte[] broadcast channel. Publish bytes, receive bytes, that's it. | Types, events, keys, routing, serialization, codecs. |

This layering is the whole point of the refactor. Today, the backend SPI
does broadcast AND the primitives + `FeederSupport` do their own routing
logic with magic strings. After the refactor, each layer has exactly one
job, and the in-process routing logic lives in one place: `DefaultNotifier`.

### Why

The current design has several smells:

1. **Magic string sentinels.** Primitives publish `"__DELETED__"` /
   `"__COMPLETED__"` as special payloads that `FeederSupport` recognizes
   by string comparison. The contract is implicit — nothing in the SPI
   signature tells you those strings are special. Grep-bait for
   anyone adding new event kinds.
2. **Client-side filtering.** `FeederSupport` subscribes to every
   notification on the node and filters by key with
   `if (!expectedKey.equals(notifiedKey)) return;`. A node running N
   feeders does N string comparisons on every notification, even for
   notifications targeted at other keys.
3. **No routing abstraction.** The backend SPI is both wire transport
   AND fan-out broadcaster. There's no place that says "here's how the
   process routes typed notifications" — it's spread across every
   primitive and the feeder.

The refactor fixes all three:

1. Typed sealed `Notification` hierarchy + `EventType` enum replaces
   string sentinels. Adding a new event kind is a one-file change.
2. `DefaultNotifier` maintains a
   `Map<PrimitiveType, Map<String, List<Consumer<Notification>>>>`
   routing table. A notification arriving for `(ATOM, "foo")` only
   fires handlers registered for `(ATOM, "foo")`. O(1) lookup instead
   of O(N) filter.
3. `DefaultNotifier` is the single place that owns routing. The SPI
   becomes pure opaque byte[] transport. Backends don't need to know
   anything about types, events, keys, or routing strategies.

### New types (substrate-core.notifier package)

```java
// Package-private — wire-level routing enum. Never escapes the notifier package.
enum PrimitiveType { ATOM, JOURNAL, MAILBOX }

// Package-private — wire-level event discriminator. Never escapes the notifier package.
enum EventType { CHANGED, COMPLETED, DELETED }

// Public — what handlers pattern-match on.
public sealed interface Notification
    permits Notification.Changed, Notification.Completed, Notification.Deleted {

  String key();

  record Changed(String key) implements Notification {}
  record Completed(String key) implements Notification {}
  record Deleted(String key) implements Notification {}
}

// Package-private — codec serializes this to byte[]. Never escapes the notifier package.
record RawNotification(String key, PrimitiveType primitiveType, EventType eventType) {}

// Public API — fully self-documenting typed methods on both producer and consumer sides.
public interface Notifier {

  // Producer — 7 methods, one per (primitive × event) combination.
  // Atoms never complete; mailbox completion is detected locally by the feeder on read.
  void notifyAtomChanged(String key);
  void notifyAtomDeleted(String key);
  void notifyJournalChanged(String key);
  void notifyJournalCompleted(String key);
  void notifyJournalDeleted(String key);
  void notifyMailboxChanged(String key);
  void notifyMailboxDeleted(String key);

  // Consumer — 3 methods, one per primitive type.
  NotifierSubscription subscribeToAtom(String key, Consumer<Notification> handler);
  NotifierSubscription subscribeToJournal(String key, Consumer<Notification> handler);
  NotifierSubscription subscribeToMailbox(String key, Consumer<Notification> handler);
}
```

- `Notification.Changed/Completed/Deleted` is what handlers receive.
  Pattern matching on the sealed hierarchy gives compile-time
  exhaustiveness checks in feeders and any future consumers.
- `PrimitiveType`, `EventType`, and `RawNotification` are all
  **package-private** to `org.jwcarman.substrate.core.notifier` —
  they're wire-serialization implementation details and never appear
  on the public `Notifier` interface.
- Every producer-side call site is typed via a dedicated method name
  (`notifyAtomDeleted(key)` instead of `notify(PrimitiveType.ATOM,
  new Notification.Deleted(key))`). Call sites become
  self-documenting one-liners.
- The seven producer methods and three consumer methods delegate
  internally to private per-type helpers on `DefaultNotifier` that
  touch the nested routing map.

### NotifierSpi changes

Collapse to opaque byte[] broadcast:

```java
public interface NotifierSpi {
  void notify(byte[] payload);
  NotifierSubscription subscribe(Consumer<byte[]> handler);
}
```

- No more `String key` / `String payload` tuple.
- No more `NotificationHandler` interface — a plain `Consumer<byte[]>`
  is enough.
- One opaque broadcast channel per backend. The byte[] contains a
  codec-encoded `RawNotification`.

### DefaultNotifier (substrate-core)

New public class in `org.jwcarman.substrate.core.notifier`:

```java
public class DefaultNotifier implements Notifier {

  private final NotifierSpi spi;
  private final Codec<RawNotification> codec;
  private final Map<PrimitiveType, Map<String, CopyOnWriteArrayList<Consumer<Notification>>>>
      index = new ConcurrentHashMap<>();

  public DefaultNotifier(NotifierSpi spi, CodecFactory codecFactory) {
    this.spi = spi;
    this.codec = codecFactory.create(RawNotification.class);
    // One SPI subscription for the entire node, held forever.
    spi.subscribe(this::onWire);
  }

  // --- Producer side: 7 typed methods ---

  @Override public void notifyAtomChanged(String key)    { publish(PrimitiveType.ATOM,    key, EventType.CHANGED); }
  @Override public void notifyAtomDeleted(String key)    { publish(PrimitiveType.ATOM,    key, EventType.DELETED); }
  @Override public void notifyJournalChanged(String key) { publish(PrimitiveType.JOURNAL, key, EventType.CHANGED); }
  @Override public void notifyJournalCompleted(String key){ publish(PrimitiveType.JOURNAL, key, EventType.COMPLETED); }
  @Override public void notifyJournalDeleted(String key) { publish(PrimitiveType.JOURNAL, key, EventType.DELETED); }
  @Override public void notifyMailboxChanged(String key) { publish(PrimitiveType.MAILBOX, key, EventType.CHANGED); }
  @Override public void notifyMailboxDeleted(String key) { publish(PrimitiveType.MAILBOX, key, EventType.DELETED); }

  private void publish(PrimitiveType type, String key, EventType event) {
    spi.notify(codec.encode(new RawNotification(key, type, event)));
  }

  // --- Consumer side: 3 typed methods ---

  @Override
  public NotifierSubscription subscribeToAtom(String key, Consumer<Notification> handler) {
    return register(PrimitiveType.ATOM, key, handler);
  }

  @Override
  public NotifierSubscription subscribeToJournal(String key, Consumer<Notification> handler) {
    return register(PrimitiveType.JOURNAL, key, handler);
  }

  @Override
  public NotifierSubscription subscribeToMailbox(String key, Consumer<Notification> handler) {
    return register(PrimitiveType.MAILBOX, key, handler);
  }

  private NotifierSubscription register(
      PrimitiveType type, String key, Consumer<Notification> handler) {
    var handlers = index
        .computeIfAbsent(type, t -> new ConcurrentHashMap<>())
        .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  // --- Wire ingestion ---

  private void onWire(byte[] payload) {
    RawNotification raw;
    try {
      raw = codec.decode(payload);
    } catch (RuntimeException e) {
      log.warn("Dropping malformed notification", e);
      return;
    }
    var byKey = index.get(raw.primitiveType());
    if (byKey == null) return;
    var handlers = byKey.get(raw.key());
    if (handlers == null) return;
    var typed = toTyped(raw);
    for (var h : handlers) {
      try {
        h.accept(typed);
      } catch (RuntimeException e) {
        log.warn("Notification handler threw", e);
      }
    }
  }

  private static Notification toTyped(RawNotification raw) {
    return switch (raw.eventType()) {
      case CHANGED   -> new Notification.Changed(raw.key());
      case COMPLETED -> new Notification.Completed(raw.key());
      case DELETED   -> new Notification.Deleted(raw.key());
    };
  }
}
```

Key design points:

- **One SPI subscription per node.** `DefaultNotifier`'s constructor
  opens it and holds it for the lifetime of the node. Every wire-level
  notification arrives at `onWire` regardless of whether any local
  subscriber cares.
- **Per-(type, key) routing.** Local dispatch is two `Map.get` calls
  followed by an iteration over a `CopyOnWriteArrayList`. A
  notification for a key nobody cares about exits in ~two lookups.
- **Handler exceptions are isolated.** A handler throwing must not
  break routing for other handlers or disable the SPI subscription.
- **Codec errors are logged and dropped.** A malformed wire message
  must not break routing.

### Primitive producer-side call changes

`DefaultAtom`, `DefaultJournal`, `DefaultMailbox` stop talking directly
to `NotifierSpi` and instead depend on `Notifier`. Each primitive's
`set`/`append`/`deliver`/`complete`/`delete` calls become:

```java
// DefaultAtom
notifier.notifyAtomChanged(key);  // on set/touch
notifier.notifyAtomDeleted(key);  // on delete

// DefaultJournal
notifier.notifyJournalChanged(key);    // on append
notifier.notifyJournalCompleted(key);  // on complete
notifier.notifyJournalDeleted(key);    // on delete

// DefaultMailbox
notifier.notifyMailboxChanged(key);  // on deliver
notifier.notifyMailboxDeleted(key);  // on delete
```

No string literals, no magic sentinels, no enum at the call site. Every
call is a trivially-readable one-liner naming exactly which event fires.

### FeederSupport consumer-side changes

`PrimitiveType` is package-private to the notifier package, so
`FeederSupport` can't take it as a parameter. Instead, each primitive
passes a **subscribe function** — a `BiFunction<String, Consumer<Notification>, NotifierSubscription>`
— that's already bound to the right typed `subscribeTo*` method. The
feeder doesn't need to know anything about primitive types.

```java
public static Runnable start(
    String key,
    BiFunction<String, Consumer<Notification>, NotifierSubscription> subscribeFn,
    NextHandoff<?> handoff,
    String threadName,
    FeederStep step) {

  AtomicBoolean running = new AtomicBoolean(true);
  Semaphore semaphore = new Semaphore(0);

  NotifierSubscription notifierSub = subscribeFn.apply(key, n -> {
    switch (n) {
      case Notification.Deleted _ -> {
        handoff.markDeleted();
        running.set(false);
      }
      case Notification.Changed _, Notification.Completed _ -> { /* wake the pump */ }
    }
    semaphore.release();
  });

  // ... existing thread-start logic unchanged ...
}
```

Primitives bind the right subscribe method at call time:

```java
// DefaultAtom.startFeeder
FeederSupport.start(key, notifier::subscribeToAtom, handoff, "substrate-atom-feeder", step);

// DefaultJournal.startFeeder
FeederSupport.start(key, notifier::subscribeToJournal, handoff, "substrate-journal-feeder", step);

// DefaultMailbox.startFeeder
FeederSupport.start(key, notifier::subscribeToMailbox, handoff, "substrate-mailbox-feeder", step);
```

- No more `if (!expectedKey.equals(notifiedKey)) return;` — the
  notifier only delivers matching events in the first place.
- No more `if (DELETED_PAYLOAD.equals(payload))` — the sealed switch
  enforces exhaustiveness at compile time.
- `PrimitiveType` stays package-private to the notifier package —
  `FeederSupport` never sees it.

### InMemoryNotifier rewrite

```java
public class InMemoryNotifier implements NotifierSpi {

  private final List<Consumer<byte[]>> handlers = new CopyOnWriteArrayList<>();

  @Override
  public void notify(byte[] payload) {
    for (var h : handlers) {
      h.accept(payload);
    }
  }

  @Override
  public NotifierSubscription subscribe(Consumer<byte[]> handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }
}
```

### Backend NotifierSpi implementations

Each of these currently takes `String key, String payload`:

- `substrate-hazelcast/.../HazelcastNotifierSpi.java`
- `substrate-nats/.../NatsNotifierSpi.java`
- `substrate-postgresql/.../PostgresNotifierSpi.java`
- `substrate-rabbitmq/.../RabbitMqNotifierSpi.java`
- `substrate-redis/.../RedisNotifierSpi.java`
- `substrate-sns/.../SnsNotifierSpi.java`

Each must be updated to the new signature: `notify(byte[])` /
`subscribe(Consumer<byte[]>)`. This is usually a simplification, not a
complication — most backends already store the payload as bytes and
convert to String via the client library's built-in codec. Removing
that conversion is a net removal of code per backend. The backends
don't need to know about `RawNotification`, `PrimitiveType`, or
`Notification` at all.

Each backend publishes to a single named channel / topic / subject /
exchange — pick whichever is natural for that backend. Suggestion:
`substrate.notifications` as a convention.

### SubstrateAutoConfiguration

Add a `DefaultNotifier` `@Bean` that wraps the `NotifierSpi` bean and
takes a `CodecFactory`:

```java
@Bean
@ConditionalOnBean({NotifierSpi.class, CodecFactory.class})
public Notifier notifier(NotifierSpi notifierSpi, CodecFactory codecFactory) {
  return new DefaultNotifier(notifierSpi, codecFactory);
}
```

Primitives and `FeederSupport` now depend on `Notifier`, not
`NotifierSpi`. Factories (`DefaultAtomFactory`, `DefaultJournalFactory`,
`DefaultMailboxFactory`) take a `Notifier` in their constructors.

### Test updates

- `InMemoryNotifierTest`: rewrite for the byte[] broadcast semantics.
- `DefaultNotifierTest`: new test class covering routing, per-type
  subscribe methods, codec round-trip, handler exception isolation,
  malformed-payload resilience.
- `FeederSupportTest`: update for the new `PrimitiveType` parameter
  and the typed notification handler.
- `DefaultAtomTest`, `DefaultJournalTest`, `DefaultMailboxTest`,
  `JournalSubscriptionTest`, `ConsumerEscapeHatchTest`: update mocks /
  stubs to match the `Notifier` interface instead of `NotifierSpi`,
  assert against the typed `Notification.*` records instead of
  `"__DELETED__"` / `"__COMPLETED__"` string literals.
- Backend `*NotifierSpiTest` classes: update to the byte[] signature.
  These tests previously built strings; they now build byte[] arrays
  (can use any stable bytes — the test is only verifying transport).
- Backend `*NotifierPropertiesTest` classes: unchanged shape, but may
  need to adapt to whatever @PropertySource defaults the backend uses.

### Deletions

- `substrate-core/src/main/java/org/jwcarman/substrate/core/notifier/NotificationHandler.java`
  — obsolete, replaced by `Consumer<byte[]>` at the SPI level.
- All string literals `"__DELETED__"` and `"__COMPLETED__"` anywhere
  in `src/main` and `src/test` of any module. Grep-check after the
  refactor: `grep -r '"__DELETED__"' substrate-* | grep -v specs` must
  come up empty (same for `"__COMPLETED__"` — except for backend-level
  storage tombstones in `CassandraJournalSpi` and `RabbitMqJournalSpi`,
  which are a separate concern and MUST be left alone).

## Acceptance criteria

- [ ] `substrate-core/.../notifier/PrimitiveType.java` exists as a **package-private** enum with ATOM, JOURNAL, MAILBOX values.
- [ ] `substrate-core/.../notifier/EventType.java` exists as a **package-private** enum with CHANGED, COMPLETED, DELETED values.
- [ ] `substrate-core/.../notifier/Notification.java` exists as a public sealed interface with three record variants (`Changed`, `Completed`, `Deleted`) and the `String key()` accessor.
- [ ] `substrate-core/.../notifier/RawNotification.java` exists as a package-private record with `(String key, PrimitiveType primitiveType, EventType eventType)`.
- [ ] `substrate-core/.../notifier/Notifier.java` exists as a public interface with 7 producer methods (`notifyAtomChanged/Deleted`, `notifyJournalChanged/Completed/Deleted`, `notifyMailboxChanged/Deleted`) and 3 consumer methods (`subscribeToAtom/Journal/Mailbox(String, Consumer<Notification>)`).
- [ ] `substrate-core/.../notifier/DefaultNotifier.java` exists as a public class implementing `Notifier`, backed by a `NotifierSpi` + `Codec<RawNotification>`. Owns the nested routing map. Opens exactly one SPI subscription at construction time.
- [ ] `substrate-core/.../notifier/NotifierSpi.java` is updated to `notify(byte[])` / `subscribe(Consumer<byte[]>)`. `NotificationHandler.java` is deleted.
- [ ] `substrate-core/.../memory/notifier/InMemoryNotifier.java` is rewritten to the new byte[] contract.
- [ ] `DefaultAtom`, `DefaultJournal`, `DefaultMailbox` depend on `Notifier` (not `NotifierSpi`) and call the typed `notifyAtomChanged` / `notifyJournalDeleted` / etc. methods at all call sites. No string literal notifications remain, no `PrimitiveType` references at call sites.
- [ ] `DefaultAtomFactory`, `DefaultJournalFactory`, `DefaultMailboxFactory` constructors take `Notifier` instead of `NotifierSpi`.
- [ ] `FeederSupport.start()` takes a `BiFunction<String, Consumer<Notification>, NotifierSubscription>` parameter (the bound-to-type subscribe function) instead of the old `(String, NotifierSpi)` pair. The handler uses a sealed-switch over `Notification` variants. No `expectedKey.equals(notifiedKey)` filter, no sentinel string compare. `PrimitiveType` is not mentioned anywhere in `FeederSupport`.
- [ ] `SubstrateAutoConfiguration` wires a `DefaultNotifier` `@Bean` around the `NotifierSpi` bean. Factories are wired with the `Notifier` bean, not the SPI bean.
- [ ] All backend `*NotifierSpi` classes in `substrate-hazelcast`, `substrate-nats`, `substrate-postgresql`, `substrate-rabbitmq`, `substrate-redis`, `substrate-sns` are updated to the byte[] signature.
- [ ] `DefaultNotifierTest` exists in `substrate-core` and covers: per-type routing, subscribe/unsubscribe, fan-out to multiple handlers for the same key, isolation between types, handler exception isolation, malformed codec payload isolation, notifications to non-subscribed keys are dropped silently (not delivered).
- [ ] `InMemoryNotifierTest` is rewritten for the byte[] contract.
- [ ] `FeederSupportTest` is updated for the `PrimitiveType` parameter and typed notification handler.
- [ ] `grep -rn '"__DELETED__"' substrate-*/src/{main,test}` returns zero results. Same for `"__COMPLETED__"` EXCEPT for `CassandraJournalSpi` and `RabbitMqJournalSpi` (backend storage tombstones — leave those alone).
- [ ] `./mvnw verify` passes from the root. `./mvnw spotless:check` passes.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- `CodecFactory.create(RawNotification.class)` must work. Verify against whatever codec library substrate currently uses (likely Jackson). If Jackson, `RawNotification`'s record constructor and field accessors are enough — no additional annotations should be needed.
- The `DefaultNotifier`'s `onWire` handler runs on whatever thread the backend's SPI dispatches on. Don't block in handlers; the `CopyOnWriteArrayList` iteration is safe for concurrent modification, but a slow handler delays delivery to subsequent handlers for the same event.
- `CopyOnWriteArrayList.remove(Object)` uses `equals`. Since we're storing lambdas / method references, each registered handler is a distinct object and `remove` will match by identity. That's correct — the returned `NotifierSubscription` captures the exact same handler reference.
- The map cleanup problem: when the last handler for a `(type, key)` pair unsubscribes, the inner map entry is left behind with an empty list. Eventually the outer map entry for a never-used `PrimitiveType` also leaks. For a long-running node with many short-lived subscriptions, this grows unboundedly. Acceptable trade-off for the first cut (keys are bounded by the number of primitives, primitive types are three); revisit if it becomes a real problem.
- `FeederSupport.start()`'s new `PrimitiveType` parameter means each of the three primitive `startFeeder` helpers passes its own `PrimitiveType.ATOM` / `.JOURNAL` / `.MAILBOX`. That's how the notifier knows which subscribe method to call.
- Wire format example for Jackson: `{"key":"substrate:atom:session-abc","primitiveType":"ATOM","eventType":"DELETED"}` — roughly 80 bytes. Still small enough for pub/sub systems that impose message size limits.
- `FeederSupport`'s handling of the notifier subscription (currently `notifierSub.cancel()` in the finally block) stays the same — just the subscription type changes. `NotifierSubscription` itself is unchanged.

## Out of scope

- Backend-native topic routing (per-primitive-type channels, etc.) —
  the single-channel broadcast is the MVP. Add routing later if
  performance demands it.
- The `"__COMPLETED__"` storage tombstones in `CassandraJournalSpi` and
  `RabbitMqJournalSpi`. Those are a different concern (backend storage
  format) and are not touched by this spec.
- Changes to the `Codec`/`CodecFactory` machinery itself. This spec
  uses whatever codec infrastructure exists today.
- Changes to the shutdown coordinator, the handoff types, or the
  subscription lifecycle. This spec only touches the notifier layer.
