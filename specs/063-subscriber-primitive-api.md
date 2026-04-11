# Switch primitive callback APIs to Subscriber<T>; merge CallbackSubscription into Subscription

## What to build

Flip `Atom`, `Journal`, and `Mailbox` from the old
`Consumer<T> onNext + Consumer<CallbackSubscriberBuilder<T>> customizer`
callback pattern to the new `Subscriber<T>` / `SubscriberConfig<T>`
types introduced in spec 062. Also collapse the now-redundant
`CallbackSubscription` marker interface into the plain `Subscription`
type — `BlockingSubscription` already extends `Subscription` and adds
`next()`, so having a second sibling marker buys nothing.

This is a **breaking change** and a large refactor touching the public
API, the three primitive impls, `DefaultCallbackSubscription`, and a
large chunk of the core test suite. Spec 062 MUST be done first.

### Public API changes (substrate-api)

**Delete:**
- `substrate-api/src/main/java/org/jwcarman/substrate/CallbackSubscription.java`
- `substrate-api/src/main/java/org/jwcarman/substrate/CallbackSubscriberBuilder.java`

**Keep but update javadoc:**
- `substrate-api/src/main/java/org/jwcarman/substrate/Subscription.java` — update class javadoc to describe both pull (`BlockingSubscription`) and push (`Subscriber<T>` + `subscribe`) delivery styles. Drop references to `CallbackSubscription`.
- `substrate-api/src/main/java/org/jwcarman/substrate/BlockingSubscription.java` — drop `@see CallbackSubscription`.
- `substrate-api/src/main/java/org/jwcarman/substrate/package-info.java` — if it mentions `CallbackSubscription`, update.

**Update subscribe methods on each primitive** — replace all
`Consumer<T> onNext` / `Consumer<CallbackSubscriberBuilder<T>> customizer`
overloads with two flavors per positional variant: one that takes a
ready-made `Subscriber<T>`, and one that takes a
`Consumer<SubscriberConfig<T>>` customizer. Return type is now plain
`Subscription`, not `CallbackSubscription`.

```java
// Atom.java
Subscription subscribe(Subscriber<Snapshot<T>> subscriber);
Subscription subscribe(Consumer<SubscriberConfig<Snapshot<T>>> customizer);
Subscription subscribe(Snapshot<T> lastSeen, Subscriber<Snapshot<T>> subscriber);
Subscription subscribe(Snapshot<T> lastSeen, Consumer<SubscriberConfig<Snapshot<T>>> customizer);

// Journal.java
Subscription subscribe(Subscriber<JournalEntry<T>> subscriber);
Subscription subscribe(Consumer<SubscriberConfig<JournalEntry<T>>> customizer);
Subscription subscribeAfter(String afterId, Subscriber<JournalEntry<T>> subscriber);
Subscription subscribeAfter(String afterId, Consumer<SubscriberConfig<JournalEntry<T>>> customizer);
Subscription subscribeLast(int count, Subscriber<JournalEntry<T>> subscriber);
Subscription subscribeLast(int count, Consumer<SubscriberConfig<JournalEntry<T>>> customizer);

// Mailbox.java
Subscription subscribe(Subscriber<T> subscriber);
Subscription subscribe(Consumer<SubscriberConfig<T>> customizer);
```

The `BlockingSubscription<T> subscribe(...)` pull-style overloads
stay exactly as they are.

The customizer-flavor impl is a one-liner on each primitive,
delegating to the `Subscriber.fromConfig(Consumer<SubscriberConfig<T>>)`
static helper introduced in spec 062:

```java
@Override
public Subscription subscribe(Consumer<SubscriberConfig<Snapshot<T>>> customizer) {
  return subscribe(Subscriber.fromConfig(customizer));
}
```

**Overload ambiguity note:** `Subscriber<T>` and `Consumer<SubscriberConfig<T>>`
are both SAMs taking one argument and returning `void`. A plain lambda
like `atom.subscribe(x -> doSomething(x))` will usually disambiguate
based on what `x` is used as in the body (a `Snapshot<T>` value vs. a
`SubscriberConfig<Snapshot<T>>` configurator). If you hit a case
where a test lambda is ambiguous, add an explicit parameter type
(`(Snapshot<String> s) -> ...`) rather than renaming the overloads.

### Core impl changes (substrate-core)

**Delete:**
- `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/DefaultCallbackSubscriberBuilder.java`
- `substrate-core/src/main/java/org/jwcarman/substrate/core/subscription/LifecycleCallbacks.java`
- `substrate-core/src/test/java/org/jwcarman/substrate/core/subscription/DefaultCallbackSubscriberBuilderTest.java`

**Rewrite `DefaultCallbackSubscription<T>`:**
- Implements `Subscription` (not `CallbackSubscription`, which is gone).
- Constructor: `DefaultCallbackSubscription(BlockingSubscription<T> source, Subscriber<T> subscriber)`.
- Handler loop dispatches each `NextResult` variant to the corresponding `Subscriber<T>` method:
  - `Value(v)` → `subscriber.onNext(v)`
  - `Timeout` → continue loop
  - `Completed` → `subscriber.onCompleted()`
  - `Expired` → `subscriber.onExpired()`
  - `Deleted` → `subscriber.onDeleted()`
  - `Cancelled` → `subscriber.onCancelled()`
  - `Errored(t)` → `subscriber.onError(t)`
- Each dispatch is wrapped so a runtime exception from a user handler is logged but does not break the loop.
- Delete the legacy 7-arg convenience constructor that took individual `Consumer`/`Runnable` handlers.

**Update `DefaultAtom`, `DefaultJournal`, `DefaultMailbox`:**
- Implement the new `subscribe(Subscriber<T>)` overloads. The `buildCallbackSubscription` helper becomes trivial: create feeder/handoff, build `DefaultBlockingSubscription`, wrap in `DefaultCallbackSubscription(source, subscriber)`.
- Implement the new `subscribe(Consumer<SubscriberConfig<T>>)` overloads as one-line adapters that delegate to `Subscriber.fromConfig(customizer)` and then call the `Subscriber<T>` flavor.
- Remove the old `Consumer<T>` / `Consumer<CallbackSubscriberBuilder<T>>` overloads entirely.
- Remove `DefaultCallbackSubscriberBuilder` and `LifecycleCallbacks` imports.

### Test updates (substrate-core)

Every test that used the old callback API needs to move to
`Subscriber<T>` (either via `SubscriberConfig<T>` or an anonymous
class):

- `substrate-core/src/test/java/org/jwcarman/substrate/core/subscription/DefaultCallbackSubscriptionTest.java` — ~17 `new DefaultCallbackSubscription<>(...)` call sites. Rewrite each one to construct a `Subscriber<T>` (builder or anon class) and pass it along with a `DefaultBlockingSubscription` source. Preserve test intent: each test still verifies the same lifecycle behavior.
- `substrate-core/src/test/java/org/jwcarman/substrate/core/atom/DefaultAtomTest.java` — ~5 `CallbackSubscription sub = atom.subscribe(...)` sites. Replace `CallbackSubscription` with `Subscription` and migrate to `Subscriber<T>` / `SubscriberConfig<T>`.
- `substrate-core/src/test/java/org/jwcarman/substrate/core/journal/DefaultJournalTest.java` — ~6 sites, same pattern.
- `substrate-core/src/test/java/org/jwcarman/substrate/core/journal/JournalSubscriptionTest.java` — ~4 sites, same pattern.
- `substrate-core/src/test/java/org/jwcarman/substrate/core/mailbox/DefaultMailboxTest.java` — ~4 sites, same pattern.

Coverage note: the existing test coverage for `DefaultCallbackSubscriberConfig`
folds into `SubscriberConfigTest` (added in spec 062). This spec's
`DefaultCallbackSubscription` tests should cover lifecycle dispatch
AND handler-exception isolation for every variant including `onCancelled`.

### Docs / other touches

- `README.md` — if it shows a callback-style subscribe example, update it.
- `CHANGELOG.md` — add an entry under the current unreleased section (or 0.2.0 if that's the next version) describing: `Subscriber<T>` introduction, removal of `CallbackSubscription` / `CallbackSubscriberBuilder`, primitive subscribe API changes. Call it out as breaking.

## Acceptance criteria

- [ ] `CallbackSubscription.java` and `CallbackSubscriberBuilder.java` are deleted from `substrate-api`.
- [ ] `LifecycleCallbacks.java`, `DefaultCallbackSubscriberBuilder.java`, and `DefaultCallbackSubscriberBuilderTest.java` are deleted from `substrate-core`.
- [ ] `Atom.java`, `Journal.java`, `Mailbox.java` each expose the exact callback subscribe overloads listed above (one `Subscriber<T>` flavor and one `Consumer<SubscriberConfig<T>>` customizer flavor per positional variant), returning plain `Subscription`. No `Consumer<T> onNext` overloads remain, no `Consumer<CallbackSubscriberBuilder<T>> customizer` overloads remain.
- [ ] `DefaultCallbackSubscription<T>` takes `(BlockingSubscription<T> source, Subscriber<T> subscriber)` and implements plain `Subscription`. The legacy 7-arg constructor is gone.
- [ ] `DefaultAtom`, `DefaultJournal`, `DefaultMailbox` compile, implement the new interface methods, and pass their existing test suites (with tests updated to the new API).
- [ ] `DefaultCallbackSubscriptionTest` is rewritten and covers: value delivery; each terminal event (`onCompleted`, `onExpired`, `onDeleted`, `onCancelled`, `onError`); a user handler throwing does not kill the handler thread and later events still dispatch; `cancel()` stops the loop promptly; interrupt exits the loop without firing `onError`; idle subscription does no periodic work.
- [ ] `DefaultAtomTest`, `DefaultJournalTest`, `JournalSubscriptionTest`, `DefaultMailboxTest` all compile and pass — use `Subscriber<T>` / `SubscriberConfig<T>` and plain `Subscription` in place of the old types.
- [ ] `README.md` and `CHANGELOG.md` reflect the change.
- [ ] `./mvnw verify` passes from the root.
- [ ] `./mvnw spotless:check` passes.
- [ ] No `CallbackSubscription` / `CallbackSubscriberBuilder` / `LifecycleCallbacks` / `DefaultCallbackSubscriberBuilder` references remain anywhere in `src/main` or `src/test` (grep check).
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- Spec 062 landed `Subscriber<T>`, `SubscriberConfig<T>`, `DefaultSubscriberConfig<T>`, and the `Subscriber.fromConfig(Consumer<SubscriberConfig<T>>)` static helper as pure additions. Start by verifying those exist and have green tests before touching anything else. Substrate-core code MUST use `Subscriber.fromConfig(...)` to materialize a `Subscriber<T>` from a customizer — it cannot instantiate `DefaultSubscriberConfig` directly (package-private in substrate-api).
- The `ShutdownCoordinator` plumbing (including `NextResult.Cancelled`, `markCancelled()`, and the layered `DefaultBlockingSubscription` + `DefaultCallbackSubscription` design) is already in place. This spec only changes the **callback side** of that design — the blocking subscription and its cancellation semantics stay exactly as they are.
- `DefaultCallbackSubscription`'s handler loop already calls `source.next(MAX_POLL_DURATION)` and switches on the `NextResult` variant. The dispatch switch just needs to call `subscriber.onXxx` instead of looking up a handler from a `LifecycleCallbacks` record.
- Wrap each `subscriber.onXxx(...)` call in a try/catch(RuntimeException) that logs via commons-logging and keeps looping. Follow the existing `safeRun` / `safeAccept` shape in the current `DefaultCallbackSubscription`.
- When rewriting `DefaultCallbackSubscriptionTest`, prefer the builder form for tests that register multiple handlers (it reads cleanly). Use anon classes only when you need to mock behavior like throwing from a specific handler.
- The three `NextHandoff<String>` anonymous subclasses in `DefaultCallbackSubscriptionTest` (around the "idle does zero periodic work", "external interrupt", and "interrupt does not fire onError" tests) already override `markCancelled()`. Keep those overrides.
- Migration shortcut for the five primitive test files: a `Subscription` variable can hold the return of a callback `subscribe`, since the method now returns plain `Subscription`. Just change the type declaration. For the lambda, prefer the customizer flavor in tests — `atom.subscribe(b -> b.onNext(s -> ...).onError(t -> ...))` — because it reads cleanly without explicit type annotations. Use the ready-made `Subscriber<T>` flavor when passing a pre-built subscriber or a method reference.
- Java lambdas can target `Subscriber<T>` as a SAM because `onNext` is its only abstract method. `atom.subscribe(snap -> log.info("{}", snap))` resolves directly to `subscribe(Subscriber<Snapshot<T>>)`.
- When rewriting the `README.md` callback example, show both a lambda-only flavor and a `SubscriberConfig` flavor so readers see the two ergonomic paths.

## Out of scope

- Backend modules (`substrate-redis`, `substrate-nats`, etc.) should not need changes — they don't depend on the callback subscribe API, only on the SPI contracts. If a backend test references `CallbackSubscription` directly, update it; otherwise leave backends alone.
- Do NOT change `BlockingSubscription<T>` behavior, `NextResult` variants, or `ShutdownCoordinator` — that work already landed.
- Do NOT rename `DefaultCallbackSubscription` even though `CallbackSubscription` is gone. The class name is fine internally; a rename is a separate cleanup if wanted later.
