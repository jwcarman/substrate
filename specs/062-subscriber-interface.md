# Introduce Subscriber<T> and SubscriberConfig<T> interfaces (+ core impls)

## What to build

Introduce the unified callback-style subscription API. This spec is
a **pure addition** — no existing types are changed, no callers are
migrated. The follow-on spec (063) flips the primitives over to use
these types and retires the old `CallbackSubscriberBuilder` /
`CallbackSubscription` scaffolding.

The split deliberately keeps `substrate-api` as pure interfaces and
parks all the implementation details in `substrate-core`:

| Module          | Type                          | Visibility       |
|-----------------|-------------------------------|------------------|
| `substrate-api` | `Subscriber<T>`               | public interface |
| `substrate-api` | `SubscriberConfig<T>`         | public interface |
| `substrate-core`| `ConfiguredSubscriber<T>`     | public record (in `org.jwcarman.substrate.core.subscription`) |
| `substrate-core`| `DefaultSubscriberConfig<T>`  | public class (same package) |

The two api interfaces are clean and contain no static factories,
no reference to any default impl, and no "build" semantics. The core
side owns the mutable config, the frozen record, and the bridge
helper that turns a `Consumer<SubscriberConfig<T>>` customizer into
a `Subscriber<T>`.

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

### `DefaultSubscriberConfig<T>` (substrate-core)

New file: `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/DefaultSubscriberConfig.java`

Public mutable concrete implementation of `SubscriberConfig<T>`.
Stores each registered handler in a field. Provides a public static
helper that bridges `Consumer<SubscriberConfig<T>>` customizers into
a `Subscriber<T>`:

```java
public final class DefaultSubscriberConfig<T> implements SubscriberConfig<T> {
  private Consumer<T> onNext;
  private Runnable onCompleted;
  // ... etc ...

  @Override public SubscriberConfig<T> onNext(Consumer<T> handler) {
    this.onNext = handler;
    return this;
  }
  // ... etc ...

  public Subscriber<T> toSubscriber() {
    if (onNext == null) {
      throw new IllegalStateException(
          "SubscriberConfig requires an onNext handler");
    }
    return new ConfiguredSubscriber<>(
        onNext, onCompleted, onExpired, onDeleted, onCancelled, onError);
  }

  public static <T> Subscriber<T> from(Consumer<SubscriberConfig<T>> customizer) {
    DefaultSubscriberConfig<T> config = new DefaultSubscriberConfig<>();
    customizer.accept(config);
    return config.toSubscriber();
  }
}
```

- `toSubscriber()` throws `IllegalStateException` if `onNext` was
  never registered — a configuration with no value handler is always
  a mistake. The primitive will surface that exception before starting
  the feeder.
- `DefaultSubscriberConfig.from(customizer)` is the canonical bridge
  the three primitives will use in spec 063 to implement their
  `Consumer<SubscriberConfig<T>>` customizer-flavor subscribe
  overloads. Public so it's reachable from `DefaultAtom`,
  `DefaultJournal`, `DefaultMailbox`.

## Acceptance criteria

- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/Subscriber.java` exists with the shape above. No static factories, no nested types, no references to any default impl.
- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/SubscriberConfig.java` exists with the shape above. No `build()` method, no static factories, no references to any default impl.
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/ConfiguredSubscriber.java` exists as a public record that implements `Subscriber<T>` with null-guarded dispatch to its handler fields.
- [ ] `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/DefaultSubscriberConfig.java` exists, implements `SubscriberConfig<T>`, and exposes both a public instance method `Subscriber<T> toSubscriber()` and a public static factory `Subscriber<T> from(Consumer<SubscriberConfig<T>>)`.
- [ ] Javadoc on `Subscriber<T>` and `SubscriberConfig<T>` explains when each method fires and shows both a direct lambda-style `Subscriber<T>` usage example and a `Consumer<SubscriberConfig<T>>` customizer usage example.
- [ ] A new test class `SubscriberTest` in `substrate-api/src/test/java/org/jwcarman/substrate/` covers: default `onCompleted`/`onExpired`/`onDeleted`/`onCancelled`/`onError` impls don't throw when called on a SAM-only `Subscriber<T>` lambda.
- [ ] A new test class `ConfiguredSubscriberTest` in `substrate-core/src/test/java/org/jwcarman/substrate/core/subscription/` covers the record directly: constructing one with a mix of `null` and non-null handler fields, then asserting that each `Subscriber<T>` method either dispatches to its handler or silently no-ops when the handler is `null`.
- [ ] A new test class `DefaultSubscriberConfigTest` in the same core package covers:
  - Each setter returns `this`.
  - `toSubscriber()` returns a `ConfiguredSubscriber<T>` whose interface methods dispatch to the registered callbacks (exercise each one).
  - `toSubscriber()` with no `onNext` registered throws `IllegalStateException`.
  - `DefaultSubscriberConfig.from(c -> c.onNext(...).onError(...))` returns a `Subscriber<T>` whose handlers dispatch correctly.
  - `DefaultSubscriberConfig.from(c -> {})` (no onNext) throws `IllegalStateException`.
- [ ] `./mvnw -pl substrate-api,substrate-core verify` passes.
- [ ] `./mvnw spotless:check` passes.
- [ ] No existing types are modified — this spec is additive only.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Keep `substrate-api` clean: no static helpers, no nested types, no
  imports from `substrate-core`. The two interfaces should look like
  pure contracts.
- `ConfiguredSubscriber<T>` and `DefaultSubscriberConfig<T>` both live
  in `org.jwcarman.substrate.core.subscription` — the same package
  that already holds `DefaultBlockingSubscription`,
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
