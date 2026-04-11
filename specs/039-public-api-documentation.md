# Thoroughly document public APIs

**Depends on: specs 018–037 must be completed.** This is a
documentation-only sweep that adds comprehensive Javadoc to every
user-facing and SPI type in substrate. No code logic changes.

## What to build

Substrate has grown a lot of public API surface across specs 018–037
(Atom, Journal, Mailbox primitives, the Subscription model,
NextResult sealed type, SPI interfaces, factories, exceptions). Much
of that surface currently has sparse or minimal Javadoc — enough for
the implementer to understand while writing the code, but not enough
for a first-time user to learn the library from the generated API
docs alone.

This spec is a documentation pass that brings every public type and
method up to a consistent quality bar:

- **Class-level javadoc** on every public class and interface
  explaining its purpose, role in the library, and a short usage
  example where applicable.
- **Method-level javadoc** on every public and protected method
  documenting parameters, return value, thrown exceptions, and any
  behavioral nuances (blocking vs non-blocking, idempotency,
  thread-safety, lifecycle constraints, etc.).
- **Package-level javadoc** (`package-info.java`) for each package,
  giving a one-paragraph overview of what lives there.
- **Cross-references** via `{@link}` and `{@see}` tags where one
  type refers to another.
- **Usage examples** in class-level javadoc for the most important
  types, showing the blocking and callback consumer patterns.

No code logic changes. No new types. No behavioral changes. Pure
documentation.

## Scope — what's in and out

### In scope: `substrate-api` (all types)

Every public type in `substrate-api`. These are user-facing by
definition — users write code against these interfaces, so the
Javadoc needs to be high quality.

**`org.jwcarman.substrate` (top-level):**
- `Subscription`
- `BlockingSubscription<T>`
- `CallbackSubscription`
- `CallbackSubscriberBuilder<T>`
- `NextResult<T>` (sealed interface + all six permitted records)

**`org.jwcarman.substrate.atom`:**
- `Atom<T>`
- `AtomFactory`
- `Snapshot<T>`
- `AtomExpiredException`
- `AtomAlreadyExistsException`

**`org.jwcarman.substrate.journal`:**
- `Journal<T>`
- `JournalFactory`
- `JournalEntry<T>`
- `JournalCompletedException`
- `JournalExpiredException`
- `JournalAlreadyExistsException`

**`org.jwcarman.substrate.mailbox`:**
- `Mailbox<T>`
- `MailboxFactory`
- `MailboxExpiredException`
- `MailboxFullException`

### In scope: SPI interfaces in `substrate-core`

These are backend-facing but they ARE public API for anyone writing
a new backend implementation. They need good Javadoc so backend
authors understand the contract they're implementing.

**`org.jwcarman.substrate.core.atom`:**
- `AtomSpi`
- `AbstractAtomSpi`
- `AtomRecord` (or `RawAtom` — match whichever name is in the code
  post-spec-037)

**`org.jwcarman.substrate.core.journal`:**
- `JournalSpi`
- `AbstractJournalSpi`
- `RawJournalEntry`

**`org.jwcarman.substrate.core.mailbox`:**
- `MailboxSpi`
- `AbstractMailboxSpi`

**`org.jwcarman.substrate.core.notifier`:**
- `NotifierSpi`
- `NotificationHandler`
- `NotifierSubscription`

**`org.jwcarman.substrate.core.subscription`:**
- `NextHandoff<T>` — this is the contract that future handoff
  strategies (if any are added) will implement
- `BlockingBoundedHandoff<T>`, `CoalescingHandoff<T>`,
  `SingleShotHandoff<T>` — each gets class-level Javadoc
  explaining the strategy semantics

### Out of scope

- **`Default*` implementation classes.** These are internal. Their
  existing sparse Javadoc is fine; they're not part of the public
  surface that users or backend authors code against.
- **`Default*Test` test classes.** No Javadoc needed on tests.
- **Backend `*Spi` implementations** (e.g., `RedisAtomSpi`,
  `PostgresJournalSpi`). These are backend-internal; users never
  see them.
- **In-memory `*Spi` implementations** (e.g., `InMemoryAtomSpi`).
  Internal fallback classes; a one-line class Javadoc is enough.
- **Record helper methods** like `record.equals()`,
  `record.hashCode()`. Java records auto-generate these and they
  don't need Javadoc.

## Documentation quality bar

For each type and method, the Javadoc should cover:

### Class/interface level

- **First sentence** is a concise one-line summary (Javadoc
  convention — this appears in the class overview and must make
  sense on its own).
- **Purpose paragraph** explaining what the type is for and how it
  fits into substrate's model. Mention the analog type if
  applicable (e.g., "distributed `AtomicReference`" for `Atom`,
  "distributed `CompletableFuture`" for `Mailbox`, "distributed
  append-only stream" for `Journal`).
- **Lifecycle notes** where relevant — when is it created, when is
  it discarded, what states can it be in, what triggers state
  transitions.
- **Thread-safety notes** — is the type thread-safe, and if so, in
  what sense? (E.g., "instances are thread-safe; concurrent
  `subscribe` calls are supported.")
- **Cross-references** to related types via `{@link}`.

### Method level

- **First sentence** is the one-line summary.
- **Behavior paragraph** for anything non-obvious.
- **`@param`** for each parameter explaining what it means and any
  constraints.
- **`@return`** explaining what's returned and in what form, or
  omitted for `void` methods.
- **`@throws`** for every declared exception AND every unchecked
  exception the method can throw in documented cases. Don't omit
  runtime exceptions just because they aren't declared — if
  `Atom.set` can throw `AtomExpiredException` on a dead atom,
  document it.
- **Cross-references** where helpful.

### Package level

- `package-info.java` for each user-facing package with a
  one-paragraph overview.
- Package overview explains what lives in this package, what the
  entry points are, and how the types relate. Keep it short — 3-5
  sentences.

## Required usage examples

Certain high-traffic classes deserve concrete code examples
embedded in their class-level Javadoc. The examples should show
the shortest correct usage of the type for the most common pattern.

**`Atom<T>` class Javadoc** should include:

```java
// Typical usage — session state stored as an Atom
Atom<Session> sessionAtom =
    atomFactory.create("session:abc", Session.class, session, Duration.ofHours(1));

// Read current state
Snapshot<Session> current = sessionAtom.get();

// Watch for changes (blocking style)
try (BlockingSubscription<Snapshot<Session>> sub = sessionAtom.subscribe(current)) {
  while (sub.isActive()) {
    switch (sub.next(Duration.ofSeconds(30))) {
      case NextResult.Value<Snapshot<Session>>(var snap) -> process(snap);
      case NextResult.Timeout<Snapshot<Session>> t -> {}
      case NextResult.Expired<Snapshot<Session>> e -> handleExpired();
      case NextResult.Deleted<Snapshot<Session>> d -> handleDeleted();
      default -> {}  // Atom has no natural Completed state
    }
  }
}

// Or callback style
try (CallbackSubscription sub = sessionAtom.subscribe(
    current,
    snap -> process(snap),
    b -> b.onExpiration(this::reconnect).onDelete(this::cleanup))) {
  waitUntilShutdown();
}
```

**`Journal<T>` class Javadoc** should include:

```java
// Producer side — append events with per-entry TTL
Journal<Event> events = journalFactory.create("events", Event.class, Duration.ofDays(1));
events.append(new OrderPlaced(...), Duration.ofHours(24));

// Consumer side — tail the journal
try (BlockingSubscription<JournalEntry<Event>> sub = events.subscribe()) {
  while (sub.isActive()) {
    switch (sub.next(Duration.ofSeconds(30))) {
      case NextResult.Value<JournalEntry<Event>>(var entry) -> process(entry.data());
      case NextResult.Timeout<JournalEntry<Event>> t -> {}
      case NextResult.Completed<JournalEntry<Event>> c -> break loop;
      case NextResult.Expired<JournalEntry<Event>> e -> handleExpired();
      case NextResult.Deleted<JournalEntry<Event>> d -> handleDeleted();
      case NextResult.Errored<JournalEntry<Event>>(var cause) -> handleError(cause);
    }
  }
}
```

**`Mailbox<T>` class Javadoc** should include:

```java
// Producer side — elicitation request-response pattern
Mailbox<Response> mailbox =
    mailboxFactory.create("elicit:" + requestId, Response.class, Duration.ofMinutes(5));

// Consumer side — wait for the single delivery (blocking)
try (BlockingSubscription<Response> sub = mailbox.subscribe()) {
  switch (sub.next(Duration.ofMinutes(5))) {
    case NextResult.Value<Response>(var response) -> handleResponse(response);
    case NextResult.Timeout<Response> t -> handleTimeout();
    case NextResult.Expired<Response> e -> handleExpired();
    case NextResult.Deleted<Response> d -> handleCancelled();
    default -> {}
  }
}

// Or callback style
try (CallbackSubscription sub = mailbox.subscribe(
    response -> handleResponse(response),
    b -> b.onExpiration(this::handleTimeout))) {
  waitForCompletion();
}
```

**`NextResult<T>` class Javadoc** should include the six variants and
a brief description of when each one is returned.

**`Subscription` class Javadoc** should explain the
`isActive`/`cancel` lifecycle contract.

**`CallbackSubscriberBuilder<T>` class Javadoc** should show a
builder example:

```java
CallbackSubscription sub = atom.subscribe(
    current,
    snap -> process(snap),        // onNext (required, positional)
    b -> b
        .onExpiration(() -> reconnect())
        .onDelete(() -> cleanup())
        .onError(err -> log.error("subscription error", err)));
```

## Specific documentation notes per type

### `Atom<T>`

- Class javadoc: "A distributed `AtomicReference` with TTL-based
  leases and change notification." Explain the coalescing semantics
  of subscriptions — subscribers see current state, not history.
- `set` — document that it throws `AtomExpiredException` on a dead
  atom AND `IllegalArgumentException` if TTL exceeds configured
  max.
- `touch` — document that it returns `false` (not throws) if the
  atom is already dead, so callers can branch on "lease
  extended" vs "need to recreate."
- `get` — document that it's synchronous, non-blocking, and
  throws `AtomExpiredException` on a dead atom (unlike
  subscription-based reads which return `NextResult.Expired`).
- `subscribe(Snapshot<T>)` — document the staleness token
  comparison semantics and what happens when `lastSeen` is
  `null`.
- `subscribe` callback variants — document that `onNext` is
  required and the customizer is optional.
- Cross-reference `AtomFactory` for construction.

### `AtomFactory`

- Class javadoc: "Factory for constructing and connecting to
  `Atom` instances."
- `create` — document that it's eager (performs backend I/O to
  write the initial value) and throws `AtomAlreadyExistsException`
  on collision.
- `connect` — document that it's lazy (no backend I/O until first
  operation) and that a dead atom is discovered on the first
  call to `get`/`set`/`touch`/`subscribe`.
- Cross-reference `Atom` for operations.

### `Journal<T>`

- Class javadoc: "A distributed append-only event stream with
  per-entry TTLs, a completion lifecycle, and monotonic ordering."
  Explain the inactivity TTL and retention TTL state machine from
  spec 024.
- `append` — document `JournalCompletedException` and
  `JournalExpiredException` as possible throws.
- `complete(Duration)` — document the retention TTL semantics:
  reads continue to work until retention elapses, then the
  journal is fully expired.
- `subscribe`, `subscribeAfter`, `subscribeLast` — explain the
  starting-position differences clearly. `subscribe()` is "tail
  from current," `subscribeAfter(id)` is "resume from checkpoint,"
  `subscribeLast(count)` is "last N then tail."
- Cross-reference `JournalFactory`, `JournalEntry`.

### `JournalFactory`, `Mailbox<T>`, `MailboxFactory`

Similar treatment — class javadoc explains purpose, methods
document exceptions, lifecycle, and semantics.

### `Snapshot<T>` and `JournalEntry<T>`

- Class javadoc explains the record's purpose and the meaning of
  each field.
- Javadoc on individual accessor methods is optional for records
  since the record declaration itself documents the fields — but
  a class-level note on "tokens are opaque staleness markers,
  compare only via equals" is worth including for `Snapshot`.

### `NextResult<T>` and its six permitted records

- Sealed interface javadoc explains the pattern-matching contract
  and enumerates the variants.
- Each record's class javadoc explains when it's returned:
  - `Value` — "A new value arrived from the underlying primitive."
  - `Timeout` — "The timeout elapsed without a new value; the
    subscription is still alive."
  - `Completed` — "The underlying primitive reached its natural
    end-of-life. Fires for Journal after `complete()` + drain, and
    for Mailbox after the single delivery has been consumed.
    Never fires for Atom."
  - `Expired` — "The underlying primitive's TTL elapsed without
    renewal."
  - `Deleted` — "The underlying primitive was explicitly deleted."
  - `Errored` — "An unexpected backend error occurred. The cause
    is in the `cause` field."
- `isTerminal()` — document the default behavior and the two
  overrides.

### `Subscription`, `BlockingSubscription<T>`, `CallbackSubscription`

- `Subscription` class javadoc explains the lifecycle contract
  and the role of `isActive` / `cancel`.
- `BlockingSubscription<T>.next(Duration)` documents the pull
  semantics, the relationship between `isActive` and terminal
  results, and the interrupt-handling behavior (subscription
  becomes inactive on thread interrupt).
- `CallbackSubscription` is a marker interface — class javadoc
  explains that it has no methods beyond `Subscription` and that
  all interaction happens via the registered handlers.

### `CallbackSubscriberBuilder<T>`

- Class javadoc: "Configures a `CallbackSubscription`'s lifecycle
  handlers. Passed to a primitive's `subscribe` method as a
  customizer lambda."
- Each `onXxx` method documents:
  - When the handler fires
  - Whether it fires for all primitives or is primitive-specific
  - Whether it's required or optional
- `onNext` is NOT on this builder — it's a positional argument.
  Document this in the class javadoc.

### SPI interfaces (`AtomSpi`, `JournalSpi`, `MailboxSpi`, `NotifierSpi`)

These need extra care because backend authors depend on them:

- Class javadoc explains the contract as a whole and what backend
  capabilities are required.
- Each method specifies exact semantics: atomicity requirements,
  exception cases, return-value semantics, thread-safety
  requirements.
- `AtomSpi.create` needs to explicitly document the
  "atomic set-if-not-exists" requirement and throw
  `AtomAlreadyExistsException` on collision.
- `JournalSpi.append` needs to document the order guarantees and
  the entry-id generation contract.
- `MailboxSpi.deliver` needs to document the
  `MailboxExpiredException` and `MailboxFullException` contract.
- `NotifierSpi.notify` + `subscribe` need to document the
  best-effort delivery semantics and the key-filtering
  convention.

### Abstract base classes (`AbstractAtomSpi`, etc.)

- Class javadoc: "Skeletal implementation of `XSpi` providing the
  key-prefix helper. Backend implementations extend this."
- Each method documents what the base class provides and what
  the subclass must implement.

### Handoff classes (`NextHandoff<T>`, `BlockingBoundedHandoff<T>`, `CoalescingHandoff<T>`, `SingleShotHandoff<T>`)

- `NextHandoff<T>` interface javadoc documents the contract as a
  whole: push semantics, pull semantics, terminal state
  handling, backpressure.
- Each implementation's class javadoc clearly states which
  strategy it embodies and which primitive uses it.
- `BlockingBoundedHandoff<T>` — "FIFO bounded queue; producer
  blocks on push when full. Used by Journal."
- `CoalescingHandoff<T>` — "Single-slot latest-wins; producer
  never blocks. Used by Atom."
- `SingleShotHandoff<T>` — "Single-push sealed; auto-transitions
  to Completed after consumption. Used by Mailbox."
- Document the edge cases each strategy handles differently:
  push-after-seal, mark-after-value, terminal stickiness.

## Package-level Javadoc

Create `package-info.java` for each user-facing package:

### `substrate-api/.../org.jwcarman.substrate/package-info.java`

```java
/**
 * Substrate subscription model — the consumer-side types that are
 * shared across all primitives. Contains {@link Subscription} and its
 * two concrete forms ({@link BlockingSubscription} and
 * {@link CallbackSubscription}), the sealed {@link NextResult} outcome
 * type, and {@link CallbackSubscriberBuilder} for configuring
 * callback-style subscriptions.
 */
package org.jwcarman.substrate;
```

### `substrate-api/.../org.jwcarman.substrate.atom/package-info.java`

```java
/**
 * The Atom primitive — a distributed, leased, keyed reference with
 * change notification. Contains the {@link Atom} interface,
 * {@link AtomFactory} for construction, {@link Snapshot} for
 * point-in-time views, and atom-specific exceptions.
 *
 * <p>Use Atom when you need "a shared variable that multiple processes
 * can read and write, with notification when it changes." See
 * {@link Atom} for the full contract and usage examples.
 */
package org.jwcarman.substrate.atom;
```

Similar treatment for `substrate.journal` and `substrate.mailbox`.

## Acceptance criteria

### substrate-api coverage

- [ ] Every public class and interface in `substrate-api` has
      class-level Javadoc with a first-sentence summary and a
      purpose paragraph.
- [ ] Every public method has Javadoc with `@param`, `@return`,
      and `@throws` tags as applicable.
- [ ] `Atom<T>`, `Journal<T>`, and `Mailbox<T>` each have a class-
      level usage example showing the typical consumer pattern.
- [ ] `Subscription`, `BlockingSubscription<T>`,
      `CallbackSubscription`, `NextResult<T>`, and
      `CallbackSubscriberBuilder<T>` all have thorough class-level
      documentation.
- [ ] Each of the six `NextResult<T>` record variants has class-
      level Javadoc explaining when it's returned.
- [ ] Every exception class has Javadoc explaining what triggers
      it.
- [ ] `package-info.java` exists for each package in `substrate-api`.

### substrate-core SPI coverage

- [ ] `AtomSpi`, `JournalSpi`, `MailboxSpi`, `NotifierSpi` have
      thorough class-level Javadoc explaining the contract,
      atomicity requirements, and exception cases.
- [ ] `AbstractAtomSpi`, `AbstractJournalSpi`, `AbstractMailboxSpi`
      have class-level Javadoc explaining what they provide and
      what subclasses must implement.
- [ ] `NextHandoff<T>`, `BlockingBoundedHandoff<T>`,
      `CoalescingHandoff<T>`, `SingleShotHandoff<T>` have class-
      level Javadoc explaining the strategy semantics and which
      primitive uses which.

### Quality checks

- [ ] `./mvnw javadoc:javadoc` runs without errors and without
      new warnings. Existing warnings that predate this spec are
      acceptable (fix them opportunistically if trivial).
- [ ] No `{@link}` references point to non-existent types or
      methods.
- [ ] No class-level Javadoc is empty or contains only a
      `{@inheritDoc}` tag.
- [ ] Generated Javadoc HTML (from `./mvnw javadoc:javadoc`)
      reads coherently for a first-time user — spot-check the
      `Atom`, `Journal`, and `Mailbox` entries and verify that
      someone unfamiliar with the codebase could learn the API
      from them.

### Build

- [ ] `./mvnw spotless:check` passes.
- [ ] `./mvnw verify` passes (unchanged — this is documentation-
      only).
- [ ] Apache 2.0 license headers preserved on every touched file.
- [ ] No `@SuppressWarnings` annotations introduced.

## Implementation notes

- **Style:** follow standard Javadoc conventions. First sentence
  is a concise summary. Subsequent paragraphs elaborate. Use
  `<p>` tags for paragraph breaks inside the Javadoc comment.
  Use `<ul>` / `<li>` for lists. Use `{@code ...}` for inline
  code references. Use `<pre>{@code ... }</pre>` for multi-line
  code blocks.
- **Don't over-document obvious things.** A record's accessor
  method `public T value()` doesn't need Javadoc if the class-
  level Javadoc already explains what the `value` field means.
  Focus the effort on types and methods where the behavior is
  non-obvious.
- **Use examples sparingly but effectively.** One usage example
  per primitive in the class-level Javadoc is enough. Don't put
  examples on every method — that's overwhelming.
- **Cross-reference aggressively.** Every time you mention a
  type name in prose, consider whether `{@link}` would make it
  a clickable reference in the generated Javadoc. Usually yes.
- **Terminal state documentation is critical.** Users need to
  understand which subscription outcomes terminate the loop and
  which don't. Make this crystal clear in the Javadoc for
  `BlockingSubscription.next`, `NextResult`, and each primitive's
  subscribe methods.
- **Thread-safety is critical to document.** Backend authors
  writing `JournalSpi` implementations need to know that `append`
  is called from multiple threads, that `sweep` may run
  concurrently with `append`, etc. Spell this out explicitly.
- **The `package-info.java` files are small but load-bearing.**
  They show up at the top of the package overview in generated
  Javadoc and are the first thing a new user sees when browsing
  the API. Get them right.
- This spec does NOT add documentation for any `Default*`
  implementation class. Implementation classes are internal; the
  API contract lives on the interface they implement. If a
  `Default*` class has a quirk worth documenting, document it on
  the interface instead.
- If generating Javadoc reveals any public method that was
  supposed to be package-private or private but got the wrong
  access modifier, fix the visibility as part of this spec. That's
  a real API bug masquerading as a documentation issue.
