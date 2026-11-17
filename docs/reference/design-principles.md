# Design Principles

The choices behind Substrate, and what they mean for code that uses it.

**Blocking first; callbacks when they fit.** The primary API is `BlockingSubscription.next(Duration)`,
built for virtual threads, where blocking is cheap and code reads top to bottom. A
`Subscriber` callback style is there for fire-and-forget handling. There is no Reactive Streams
dependency — no `Mono`, no `Flux`.

**Everything is leased.** Every primitive has an explicit TTL, and nothing lives forever unless
something renews it. A crashed node leaves nothing behind for long, and ceilings stop a mistake
from creating data that outlives its purpose.

**One subscription model.** Atom, Journal and Mailbox share `BlockingSubscription`, `Subscriber`
and `NextResult`. Learn it once; every outcome is a case of one sealed type the compiler can check.

**SPIs are pure storage.** Backends read and write bytes. Threads, subscriptions, codecs,
encryption and notifications live in the core, once, so every backend gets them the same way and
a new backend is a storage adapter, not a framework.

**Notifications are hints.** Nodes tell each other about changes so subscribers react at once, but
correctness never depends on a notification arriving: every subscription also re-reads on its
own.

**Use what you already run.** Each backend is one module, auto-configured from the client your
application already has. Modules can be mixed — Redis for Atom, PostgreSQL for Journal — and
anything missing falls back to an in-memory implementation with a warning, so you can start
before choosing infrastructure.
