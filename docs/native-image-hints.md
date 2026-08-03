# GraalVM native-image support in substrate

Substrate ships the AOT metadata a native image needs for its own internals. This
document records what ships, why only one type needs an explicit hint, and what
remains the consumer's responsibility.

Shipped in 0.7.0. The analysis behind it was done against a Spring Boot 4.0.5 /
Java 25 application using the GraalVM tracing agent
(`-agentlib:native-image-agent`).

## What substrate ships

One `RuntimeHintsRegistrar` in `substrate-core`:

- `substrate-core/src/main/java/org/jwcarman/substrate/core/notifier/SubstrateRuntimeHints.java`
- registered via `substrate-core/src/main/resources/META-INF/spring/aot.factories`
- covered by `substrate-core/src/test/java/org/jwcarman/substrate/core/notifier/SubstrateRuntimeHintsTest.java`

It registers Jackson binding hints for exactly one type, `RawNotification`:

```java
private static final BindingReflectionHintsRegistrar BINDING =
    new BindingReflectionHintsRegistrar();

@Override
public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
  BINDING.registerReflectionHints(hints.reflection(), RawNotification.class);
}
```

## Why `RawNotification` is the one type that needs it

`DefaultNotifier` builds its codec at runtime from a `Class` literal:

```java
private final Codec<RawNotification> codec;

public DefaultNotifier(NotifierSpi spi, CodecFactory codecFactory) {
    this.codec = codecFactory.create(RawNotification.class);   // invisible to build-time AOT
}
```

Spring AOT derives hints from static bean definitions, so it cannot see this.
Without binding hints Jackson cannot reflect on the record's components (`key`,
`primitiveType`, `eventType`) in a native image, and every notify/receive fails.

`BindingReflectionHintsRegistrar` walks record components transitively, so
registering `RawNotification` also covers its nested `PrimitiveType` and
`EventType` enums — no separate registration needed for those.

## Why nothing else needs explicit hints

The tracing agent observed reflective access on 33 substrate types, grouped by
subsystem:

- **atom** — `AtomFactory` (api), `AtomSpi`, `AbstractAtomSpi`, `DefaultAtomFactory`, `InMemoryAtomSpi`
- **journal** — `JournalFactory` (api), `JournalSpi`, `AbstractJournalSpi`, `DefaultJournalFactory`, `InMemoryJournalSpi`
- **mailbox** — `MailboxFactory` (api), `MailboxSpi`, `AbstractMailboxSpi`, `DefaultMailboxFactory`, `InMemoryMailboxSpi`
- **notifier** — `Notifier` (iface), `NotifierSpi`, `DefaultNotifier`, `InMemoryNotifier`, `RawNotification`, `EventType` (enum), `PrimitiveType` (enum)
- **lifecycle** — `ShutdownCoordinator`
- **sweep** — `Sweeper`, `Sweepable`
- **transform** — `PayloadTransformer`, `PayloadTransformer$1` (anon class — default no-op)
- **autoconfigure** — `SubstrateAutoConfiguration`, `SubstrateProperties` + 4 nested (`AtomProperties`, `JournalProperties`, `MailboxProperties`, `SubscriptionProperties`)

All but one category is already handled:

| Category | Handled by |
|---|---|
| Auto-config + `@ConfigurationProperties` | Spring AOT |
| SPI interfaces + concrete Spring beans (atom/journal/mailbox/notifier/lifecycle/sweep factories + SPIs + in-memory impls) | Spring AOT |
| User-supplied payload types stored via atom/journal/mailbox codecs | Consumer responsibility |
| Substrate's own internal wire types | `SubstrateRuntimeHints` |

## Consumer responsibility

Substrate registers hints only for its own internal wire types. Payload types you
store through an atom, journal, or mailbox are yours to register — substrate has
no way to know them at build time. If a native image round-trips substrate's
notifications correctly but fails deserializing your own payload, that is the
gap.

## Known gaps and caveats

- **`PayloadTransformer$1`** in the agent output is the default no-op transformer,
  an anonymous class inside `PayloadTransformer`. Spring AOT typically inlines
  references to such defaults. If a native build surfaces a gap here, convert the
  anonymous class to a named nested class or a static constant and register it
  explicitly.
- **Backend modules were not individually exercised.** The analysis ran against an
  application using the in-memory and Redis paths. A backend that introduces its
  own wire type or its own codec boundary would need the same treatment — repeat
  the agent analysis against a fixture for that backend and either extend
  `SubstrateRuntimeHints` or add a module-local registrar.
- **Java-serialized `EntryProcessor`s.** `substrate-hazelcast` ships `SetProcessor`,
  `CompareAndSetProcessor`, and `AtomEntry`, which travel between cluster members
  via Java serialization. These are not covered by any hint today. Not a
  regression — `AtomEntry` has had this exposure since the module was written —
  but native-image support for the Hazelcast backend would need serialization
  hints for all three.

## Verifying a change

Bump a native-image consumer application to the substrate version under test and
run:

```
mvn -Pnative spring-boot:build-image -DBP_NATIVE_IMAGE=true
```

If the native binary boots and a substrate-backed round-trip completes cleanly,
the hints are correct.
