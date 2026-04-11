# Triage Sonar S2925: Thread.sleep() uses in tests

## What to build

SonarCloud flags **12 occurrences** of `java:S2925` (MINOR,
CODE_SMELL): "Remove this use of 'Thread.sleep()'." The rule fires
for any `Thread.sleep` call in test code, regardless of purpose.

**This cleanup is not purely mechanical.** Three legitimate
categories of `Thread.sleep` exist in test code:

1. **"Assert absence" pattern** — sleep for a bounded window, then
   assert that something did NOT happen (e.g., "after 500ms no
   event was delivered"). Awaitility's `await().during(...)`
   covers some of these, but for many cases a raw sleep is the
   clearest expression.
2. **Polling workarounds** — sleep-and-check loops that should be
   Awaitility's `await().atMost(...).until(...)`. These are real
   bugs and should be fixed.
3. **TTL / expiry waits** — tests that need real wall-clock time
   to pass for a TTL to expire. These can often be replaced by
   Awaitility but occasionally need to stay as-is.

The goal of this spec is to **triage each site** and apply the
appropriate fix (or leave as-is with a short justification
comment), not to blanket-remove every sleep.

## The 12 sites

From the Sonar scan at commit `d28f69c`:

- `substrate-core/.../core/subscription/FeederSupportTest.java:164`
- `substrate-core/.../core/subscription/DefaultBlockingSubscriptionTest.java:182, 206`
- `substrate-core/.../core/subscription/DefaultCallbackSubscriptionTest.java:251, 322`
- …7 more (drive from live Sonar query)

Live query:
```
https://sonarcloud.io/api/issues/search?componentKeys=jwcarman_substrate&resolved=false&rules=java:S2925&ps=500
```

## Triage rubric

For each site, classify it into one of these buckets:

### Bucket A — Replace with Awaitility (real polling fix)

Pattern to look for:
```java
Thread.sleep(100);
assertThat(something).isTrue();   // relies on timing
```

Fix: `await().atMost(Duration.ofSeconds(N)).until(() -> something);`

### Bucket B — Replace with `await().during(...)` (assert absence)

Pattern to look for:
```java
Thread.sleep(500);
assertThat(somethingNeverHappened).isTrue();   // asserts absence
```

Fix: `await().during(Duration.ofMillis(500)).until(() -> somethingNeverHappened);`
or `await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofMillis(501)).untilAsserted(...)`.

### Bucket C — Legitimate sleep, must stay

Tests that need real wall-clock time to pass — e.g., waiting for
a TTL to expire, or deliberately racing a scheduled background
task. When the sleep is load-bearing and has no Awaitility
equivalent, leave it and add a short comment explaining why,
then suppress the rule via a project-wide rule exclusion OR
accept the Sonar hit.

**Do not add `@SuppressWarnings` annotations** — project policy
forbids them. If the sleep must stay, either (a) extract to a
helper method whose name documents the intent (e.g.,
`waitForTtlExpiry`) so the Sonar hit is at least clustered, or
(b) accept the hit in exchange for code clarity.

## Acceptance criteria

- [ ] Every flagged site is reviewed and triaged into Bucket A,
      B, or C with a one-line justification captured in the PR
      description or commit message.
- [ ] Bucket A sites are rewritten to use `await().until(...)`.
- [ ] Bucket B sites are rewritten to use `await().during(...)`
      or `await().pollDelay(...)` where the Awaitility form is
      clearer than the raw sleep.
- [ ] Bucket C sites are left as-is and briefly commented
      (one line) explaining why the sleep is load-bearing.
- [ ] `./mvnw verify` passes locally — no test regresses. Pay
      extra attention to the subscription tests in
      `substrate-core`, where timing is notoriously tricky.
- [ ] SonarCloud `java:S2925` count drops from 12 to as low as
      triage allows. Zero is the aspiration, but if 2-3
      legitimate Bucket C sleeps remain, that's acceptable.
- [ ] No new `@SuppressWarnings` annotations introduced.

## Implementation notes

- The pattern I've been seeing in `FeederSupportTest` and
  `DefaultBlockingSubscriptionTest` is mostly Bucket B — "push
  a value, sleep briefly, then verify no additional value was
  delivered." Awaitility's `during()` or `pollDelay()` cover
  these cleanly.
- Subscription timing tests should NOT have their timeouts
  reduced during this cleanup. If a test currently sleeps for
  500ms, don't drop it to 100ms "because Awaitility is faster"
  — the sleep length usually reflects a conservative bound on
  the event under test.
- Use `await().timeout(Duration.ofSeconds(5))` generously in
  Bucket A fixes. CI is slower than local.
- Do not remove `Thread.sleep` from NON-test source code in
  this spec. Main-source sleeps (if any) are out of scope.
- If a single test method contains multiple sleeps that form
  a single pattern (setup → sleep → check → sleep → check),
  consider rewriting the whole method with a single Awaitility
  call rather than incrementally fixing each sleep.
