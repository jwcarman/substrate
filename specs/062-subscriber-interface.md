# Introduce Subscriber<T> and SubscriberConfig<T> in substrate-api

## What to build

Two new public types in `substrate-api` that will become the unified
callback-style subscription API. This spec is a **pure addition** — no
existing types are changed, no callers are migrated. The follow-on
spec (063) flips the primitives over to use these types and retires
the old `CallbackSubscriberBuilder` / `CallbackSubscription` scaffolding.

### `Subscriber<T>`

New file: `substrate-api/src/main/java/org/jwcarman/substrate/Subscriber.java`

A SAM-friendly listener interface. Only `onNext` is abstract; all
terminal-event methods have no-op defaults so callers can implement
just what they care about.

```java
public interface Subscriber<T> {
  void onNext(T value);
  default void onCompleted() {}
  default void onExpired() {}
  default void onDeleted() {}
  default void onCancelled() {}
  default void onError(Throwable cause) {}

  /**
   * Materializes a {@link Subscriber} from a fluent {@link SubscriberConfig}
   * customizer. This is the bridge substrate-core uses to implement the
   * customizer-flavor {@code subscribe(Consumer<SubscriberConfig<T>>)}
   * overloads on each primitive. End users rarely call this directly —
   * they go through the primitive's subscribe method.
   */
  static <T> Subscriber<T> fromConfig(Consumer<SubscriberConfig<T>> customizer) {
    DefaultSubscriberConfig<T> config = new DefaultSubscriberConfig<>();
    customizer.accept(config);
    return config.toSubscriber();
  }
}
```

- `onNext` fires for each delivered value.
- `onCompleted` fires on natural termination (journal complete, mailbox delivered).
- `onExpired` fires when the underlying primitive's TTL elapses.
- `onDeleted` fires on explicit `delete()`.
- `onCancelled` fires when the local subscription is torn down (user
  `cancel()` or `ShutdownCoordinator.stop()`). The underlying primitive
  is unaffected.
- `onError` fires on an unexpected exception from the feeder.

Users who want a reusable subscriber implement this interface directly
(via lambda for onNext-only or anonymous class for multi-handler). Users
who want a fluent per-call configuration use the customizer flavor of
`subscribe(...)` that takes a `Consumer<SubscriberConfig<T>>` — see
spec 063.

### `SubscriberConfig<T>`

New file: `substrate-api/src/main/java/org/jwcarman/substrate/SubscriberConfig.java`

A fluent configuration interface used inside the
`Consumer<SubscriberConfig<T>>` customizer passed to `subscribe(...)`.
Deliberately has **no** `build()` method on its public surface —
materializing the `Subscriber<T>` from a config is an internal
responsibility of the primitive that owns the customizer call.

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

Intended usage (wired up in spec 063):

```java
atom.subscribe(config -> config
    .onNext(snap -> process(snap))
    .onError(err -> log.error("subscription error", err))
    .onExpired(() -> reconnect()));
```

Note: `SubscriberConfig<T>` is **only** useful inside a customizer
lambda. It is not a general-purpose builder — users cannot construct
one standalone and turn it into a `Subscriber<T>`. If they want a
reusable subscriber, they implement `Subscriber<T>` directly.

### `DefaultSubscriberConfig<T>` (package-private)

New file: `substrate-api/src/main/java/org/jwcarman/substrate/DefaultSubscriberConfig.java`

Package-private concrete implementation of `SubscriberConfig<T>`.
Stores each registered handler in a field. Exposes a package-private
method `Subscriber<T> toSubscriber()` that returns a `Subscriber<T>`
whose interface methods dispatch to the stored handlers. Unset
handlers become no-ops (matching `Subscriber<T>` default method
behavior).

`toSubscriber()` MUST throw `IllegalStateException` if `onNext` was
never registered — a configuration with no value handler is always a
mistake. The primitive will surface that exception before starting
the feeder.

## Acceptance criteria

- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/Subscriber.java` exists with the shape above, including the static `fromConfig(Consumer<SubscriberConfig<T>>)` helper that delegates to `DefaultSubscriberConfig.toSubscriber()`.
- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/SubscriberConfig.java` exists with the shape above.
- [ ] `substrate-api/src/main/java/org/jwcarman/substrate/DefaultSubscriberConfig.java` exists, is package-private, and implements `SubscriberConfig<T>` with a package-private `toSubscriber()` method.
- [ ] `SubscriberConfig<T>` has no `build()` method and no public method that returns a `Subscriber<T>`. Only the package-private `toSubscriber()` on the default impl can produce one.
- [ ] Javadoc on both public types explains when each method fires, uses `@link` back to `Subscription`, and shows both a direct `Subscriber<T>` usage example (lambda or anon class) and a `Consumer<SubscriberConfig<T>>` customizer usage example.
- [ ] A new test class `SubscriberConfigTest` in `substrate-api/src/test/java/org/jwcarman/substrate/` (same package so it can exercise the package-private impl) covers:
  - Each setter returns `this`.
  - `DefaultSubscriberConfig.toSubscriber()` returns a `Subscriber<T>` whose interface methods dispatch to the registered callbacks (exercise each one: `onNext`, `onCompleted`, `onExpired`, `onDeleted`, `onCancelled`, `onError`).
  - Unset handlers on the returned `Subscriber<T>` are no-ops (calling them throws nothing).
  - `toSubscriber()` with no `onNext` registered throws `IllegalStateException`.
- [ ] A new test class `SubscriberTest` covers:
  - Default `onCompleted`/`onExpired`/`onDeleted`/`onCancelled`/`onError` impls don't throw when called on a SAM-only `Subscriber<T>` lambda.
  - `Subscriber.fromConfig(c -> c.onNext(...).onError(...))` returns a `Subscriber<T>` whose handlers dispatch correctly.
  - `Subscriber.fromConfig(c -> {})` (no onNext) propagates `IllegalStateException` from the underlying `toSubscriber()` call.
- [ ] `./mvnw -pl substrate-api verify` passes.
- [ ] `./mvnw spotless:check` passes.
- [ ] No existing types are modified — this spec is additive only.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- `substrate-api` has no dependencies on `substrate-core`, so the
  default impl MUST live in `substrate-api` itself. Keep
  `DefaultSubscriberConfig<T>` package-private.
- Both `Subscriber<T>` and `SubscriberConfig<T>` are public top-level
  interfaces. Do NOT nest the config inside `Subscriber`.
- The existing `Subscription`, `BlockingSubscription`,
  `CallbackSubscription`, and `CallbackSubscriberBuilder` types MUST
  remain untouched in this spec. Spec 063 will retire the
  callback-specific ones.
- The existing `subscribe(...)` overloads on `Atom`/`Journal`/`Mailbox`
  MUST remain untouched in this spec. Spec 063 will replace them.
- Follow the repo copyright header convention (see any file in substrate-api).
- Java 25 source level.
