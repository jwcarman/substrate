# Exceptions

Every exception Substrate throws is unchecked. Each primitive has its own set, and each
exception's message names the backend key involved.

## Atom

| Exception | Thrown by | When |
|---|---|---|
| `AtomAlreadyExistsException` | `AtomFactory.create` | An atom with that name exists. |
| `AtomNotFoundException` | the first operation on a `connect` handle | No atom has that name. |
| `AtomExpiredException` | `get`, `set`, `compareAndSet` | The lease has run out or the atom was deleted. |

`touch` never throws `AtomExpiredException`; it returns `false`. `compareAndSet` returns `false`
when another writer got there first.

## Journal

| Exception | Thrown by | When |
|---|---|---|
| `JournalAlreadyExistsException` | `JournalFactory.create` | A journal with that name exists. |
| `JournalNotFoundException` | the first operation on a `connect` handle | No journal has that name. |
| `JournalCompletedException` | `append` | The journal has been completed. |
| `JournalExpiredException` | `append`, `complete`; on PostgreSQL and the in-memory backend also `subscribe` and `subscribeLast` | The journal expired or was deleted. |

Not every backend raises `JournalAlreadyExistsException`, `JournalCompletedException` and
`JournalExpiredException`; see [How backends differ](../primitives/journal.md#how-backends-differ).

## Mailbox

| Exception | Thrown by | When |
|---|---|---|
| `MailboxNotFoundException` | the first operation on a `connect` handle | No mailbox has that name. |
| `MailboxFullException` | `deliver` | A value has already been delivered. |
| `MailboxExpiredException` | `deliver` | The mailbox expired or was deleted. |

There is no `MailboxAlreadyExistsException`: `MailboxFactory.create` replaces an existing mailbox.

## From the JDK

| Exception | When |
|---|---|
| `IllegalArgumentException` | A TTL is negative, above its [ceiling](../concepts/index.md#upper-limits), or zero where it must be positive. Also an unknown key ID in a `SecretKeyResolver`. |
| `NullPointerException` | `compareAndSet` is given a `null` snapshot. |
| `IllegalStateException` | `substrate.crypto.shared-key` is not 16 or 32 bytes (at startup), or a payload fails to decrypt. |

Failures of the backend itself — a lost connection, a timeout — surface as the backend client's
exception; see [When the backend is unavailable](../guides/operating.md#when-the-backend-is-unavailable).

## In subscriptions

Subscriptions don't throw for the primitive's lifecycle; they report it. Expiry, deletion,
completion, cancellation and failures arrive as [`NextResult`](../concepts/index.md#outcomes)
cases, or as the matching `Subscriber` callbacks. The exceptions above are thrown by `subscribe`
methods only for a `connect` handle whose primitive doesn't exist, and, on PostgreSQL and the
in-memory backend, by a Journal `subscribe` or `subscribeLast` on an expired journal.
