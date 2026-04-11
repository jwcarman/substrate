# Introduce Subscriber<T> / SubscriberConfig<T> (api) and ConfiguredSubscriber / DefaultSubscriberBuilder (core)

## What to build

Introduce the unified callback-style subscription API. This spec is
a **pure addition** — no existing types are changed, no callers are
migrated. The follow-on spec (063) flips the primitives over to use
these types and retires the old `CallbackSubscriberBuilder` /
`CallbackSubscription` scaffolding.

The split deliberately keeps `substrate-api` as pure interfaces and
parks all the implementation details in `substrate-core`:

| Module          | Type                         | Visibility       |
|-----------------|------------------------------|------------------|
| `substrate-api` | `Subscriber<T>`              | public interface |
| `substrate-api` | `SubscriberConfig<T>`        | public interface |
| `substrate-core`| `ConfiguredSubscriber<T>`    | public record (in `org.jwcarman.substrate.core.subscription`) |
| `substrate-core`| `DefaultSubscriberBuilder<T>`| public class (same package) |

The two api interfaces are clean and contain no static factories,
no reference to any default impl, and no "build" semantics exposed
as part of the contract. The core side owns the mutable builder,
the frozen record that implements `Subscriber<T>`, and the bridge
helper that turns a `Consumer<SubscriberConfig<T>>` customizer into
a `Subscriber<T>`.

Naming choice: the user-facing interface is `SubscriberConfig<T>`
(what they see when the customizer lambda runs — a thing to be
*configured*). The core-side implementation is
`DefaultSubscriberBuilder<T>` — it implements the `SubscriberConfig<T>`
contract AND adds a `build()` method that materializes the frozen
`ConfiguredSubscriber<T>` record. Classic builder pattern with the
contract and the builder wearing different names on purpose.

### `Subscriber<T>` (substrate-api)

New file: `substrate-api/src/main/java/org/jwcarman/substrate/Subscriber.java`

```java
public interface Subscriber<T> {
  void onNext(T value);
  default void onCompleted() {}
  default void onExpired() {}
  default void onDeleted() {}
  default void onCancelled() {}
  default void onError(Throwable cause) {}
}
```

- SAM-friendly — a plain lambda targets `onNext`.
- All terminal-event methods have no-op defaults so callers can
  implement just what they care about.
- `onCompleted` fires on natural termination (journal complete,
  mailbox delivered).
- `onExpired` fires when the underlying primitive's TTL elapses.
- `onDeleted` fires on explicit `delete()`.
- `onCancelled` fires when the local subscription is torn down
  (user `cancel()` or `ShutdownCoordinator.stop()`). The underlying
  primitive is unaffected.
- `onError` fires on an unexpected exception from the feeder.

No static methods, no nested types. Just the interface.

### `SubscriberConfig<T>` (substrate-api)

New file: `substrate-api/src/main/java/org/jwcarman/substrate/SubscriberConfig.java`

```java
public interface SubscriberConfig<T> {
  SubscriberConfig<T> onNext(Consumer<T> handler);
  SubscriberConfig<T> onCompleted(Runnable handler);
  SubscriberConfig<T> onExpired(Runnable handler);
  SubscriberConfig<T> onDeleted(Runnable handler);
  SubscriberConfig<T> onCancelled(Runnable handler);
  SubscriberConfig<T> onError(Consumer<Throwable> handler);
}
```

- Fluent configuration interface used inside the
  `Consumer<SubscriberConfig<T>>` customizer passed to a primitive's
  `subscribe(...)` method.
- Deliberately has **no** `build()` method and no factory on its
  surface — materializing a `Subscriber<T>` is a substrate-core
  detail.
- No static methods, no nested types.

### `ConfiguredSubscriber<T>` (substrate-core)

New file: `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/ConfiguredSubscriber.java`

A public record in `substrate-core` that implements `Subscriber<T>`
by holding the six handler fields and dispatching each interface
method to the corresponding field (null-guarded so unset handlers
become no-ops):

```java
public record ConfiguredSubscriber<T>(
    Consumer<T> onNextHandler,
    Runnable onCompletedHandler,
    Runnable onExpiredHandler,
    Runnable onDeletedHandler,
    Runnable onCancelledHandler,
    Consumer<Throwable> onErrorHandler)
    implements Subscriber<T> {

  @Override public void onNext(T value) {
    if (onNextHandler != null) onNextHandler.accept(value);
  }
  // ... similar guarded dispatch for onCompleted, onExpired, onDeleted,
  //     onCancelled, onError ...
}
```

Named as a record (rather than an anonymous inner class emitted by a
builder) so it's easy to read in stack traces, trivial to test, and
obviously immutable once constructed. Public so other core-level
types (and tests) can reference it by name.

### `DefaultSubscriberBuilder<T>` (substrate-core)

New file: `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/DefaultSubscriberBuilder.java`

Public mutable concrete implementation of `SubscriberConfig<T>`.
Stores each registered handler in a field. Has a public `build()`
method that materializes a `ConfiguredSubscriber<T>` record, and a
public static `from(...)` bridge helper the primitives use to turn
a customizer lambda into a finished `Subscriber<T>`:

```java
public final class DefaultSubscriberBuilder<T> implements SubscriberConfig<T> {
  private Consumer<T> onNext;
  private Runnable onCompleted;
  // ... etc ...

  // Covariant return: the concrete builder type, not SubscriberConfig<T>.
  // This keeps .build() reachable after chaining without a cast.
  @Override public DefaultSubscriberBuilder<T> onNext(Consumer<T> handler) {
    this.onNext = handler;
    return this;
  }
  // ... five more setters, each declared with DefaultSubscriberBuilder<T>
  //     as the return type (covariant override of SubscriberConfig<T>'s
  //     SubscriberConfig<T> return type) ...

  public ConfiguredSubscriber<T> build() {
    if (onNext == null) {
      throw new IllegalStateException(
          "SubscriberConfig requires an onNext handler");
    }
    return new ConfiguredSubscriber<>(
        onNext, onCompleted, onExpired, onDeleted, onCancelled, onError);
  }

  public static <T> Subscriber<T> from(Consumer<SubscriberConfig<T>> customizer) {
    DefaultSubscriberBuilder<T> builder = new DefaultSubscriberBuilder<>();
    customizer.accept(builder);
    return builder.build();
  }
}
```

Covariant returns matter here. The interface method
`SubscriberConfig<T>.onNext(...)` declares a return type of
`SubscriberConfig<T>`. Java lets an implementing class override that
with a narrower return type — in this case, `DefaultSubscriberBuilder<T>`
— as long as the narrower type is a subtype of the declared return.
That means:

```java
ConfiguredSubscriber<String> sub = new DefaultSubscriberBuilder<String>()
    .onNext(s -> process(s))
    .onError(err -> log.error("boom", err))
    .build();           // <-- works: .onError() returned the concrete
                        //     DefaultSubscriberBuilder, so .build() is
                        //     still in scope.
```

Inside the customizer-flavor bridge (the `from(...)` helper), we
accept a `Consumer<SubscriberConfig<T>>` so the user's lambda sees
only the interface. But the static method creates a concrete
`DefaultSubscriberBuilder<T>`, passes it to the customizer (widened
to the interface type for the consumer's perspective), then calls
`build()` on the concrete reference.

- `build()` throws `IllegalStateException` if `onNext` was never
  registered — a configuration with no value handler is always a
  mistake. The primitive will surface that exception before starting
  the feeder.
- `build()`'s return type is `ConfiguredSubscriber<T>` (not
  `Subscriber<T>`) so callers that want the concrete record can get
  it without a cast.
- `DefaultSubscriberBuilder.from(customizer)` is the canonical bridge
  the three primitives will use in spec 063 to implement their
  `Consumer<SubscriberConfig<T>>` customizer-flavor subscribe
  overloads. Public so it's reachable from `DefaultAtom`,
  `DefaultJournal`, `DefaultMailbox`.

## Acceptance criteria

- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/Subscriber.java` exists with the shape above. No static factories, no nested types, no references to any default impl.
- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/SubscriberConfig.java` exists with the shape above. No `build()` method, no static factories, no references to any default impl.
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/ConfiguredSubscriber.java` exists as a public record that implements `Subscriber<T>` with null-guarded dispatch to its handler fields.
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/DefaultSubscriberBuilder.java` exists, implements `SubscriberConfig<T>`, declares each of its six fluent setters with a covariant return type of `DefaultSubscriberBuilder<T>` (not `SubscriberConfig<T>`), exposes a public instance method `ConfiguredSubscriber<T> build()`, and exposes a public static factory `Subscriber<T> from(Consumer<SubscriberConfig<T>>)`.
- [ ] Javadoc on `Subscriber<T>` and `SubscriberConfig<T>` explains when each method fires and shows both a direct lambda-style `Subscriber<T>` usage example and a `Consumer<SubscriberConfig<T>>` customizer usage example.
- [ ] A new test class `SubscriberTest` in `substrate-api/src/test/java/org/jwcarman/substrate/` covers: default `onCompleted`/`onExpired`/`onDeleted`/`onCancelled`/`onError` impls don't throw when called on a SAM-only `Subscriber<T>` lambda.
- [ ] A new test class `ConfiguredSubscriberTest` in `substrate-core/src/test/java/org/jwcarman/substrate/core/subscription/` covers the record directly: constructing one with a mix of `null` and non-null handler fields, then asserting that each `Subscriber<T>` method either dispatches to its handler or silently no-ops when the handler is `null`.
- [ ] A new test class `DefaultSubscriberBuilderTest` in the same core package covers:
  - Each setter returns `this`.
  - `build()` returns a `ConfiguredSubscriber<T>` whose interface methods dispatch to the registered callbacks (exercise each one).
  - `build()` with no `onNext` registered throws `IllegalStateException`.
  - `DefaultSubscriberBuilder.from(c -> c.onNext(...).onError(...))` returns a `Subscriber<T>` whose handlers dispatch correctly.
  - `DefaultSubscriberBuilder.from(c -> {})` (no onNext) throws `IllegalStateException`.
- [ ] `./mvnw -pl substrate-api,substrate-core verify` passes.
- [ ] `./mvnw spotless:check` passes.
- [ ] No existing types are modified — this spec is additive only.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Keep `substrate-api` clean: no static helpers, no nested types, no
  imports from `substrate-core`. The two interfaces should look like
  pure contracts.
- `ConfiguredSubscriber<T>` and `DefaultSubscriberBuilder<T>` both
  live in `org.jwcarman.substrate.core.subscription` — the same
  package that already holds `DefaultBlockingSubscription`,
  `DefaultCallbackSubscription`, and the handoff types. That keeps
  package-private access straightforward if we want to narrow
  visibility later.
- The existing `CallbackSubscription`, `CallbackSubscriberBuilder`,
  and primitive `subscribe(...)` overloads MUST remain untouched in
  this spec. Spec 063 will retire them.
- Follow the repo copyright header convention (see any file in
  substrate-api or substrate-core).
- Java 25 source level. Prefer records and pattern matching where
  they fit.
