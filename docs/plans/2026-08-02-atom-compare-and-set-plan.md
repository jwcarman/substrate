# Atom compare-and-set Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a token-conditional `compareAndSet` write to the `Atom` API, implemented atomically across all nine backends.

**Architecture:** The `Snapshot` token becomes a per-write random nonce instead of a content hash, making it a sound CAS basis. A new `AtomSpi.compareAndSet` returns a three-valued `CasResult` (`COMMITTED` / `TOKEN_MISMATCH` / `ABSENT`) so `DefaultAtom` can distinguish a retryable loss from a terminal expiry. Each backend implements the conditional write with its own native atomic primitive.

**Tech Stack:** Java 25, Maven, JUnit 5, AssertJ, Awaitility, Testcontainers, Spring Boot autoconfiguration.

**Design doc:** `docs/plans/2026-08-02-atom-compare-and-set-design.md`

## Global Constraints

- **Never suppress warnings.** No `@SuppressWarnings`, no `// NOSONAR`. Fix the underlying issue.
- **No star imports.** Explicit single-symbol imports only, including static imports.
- Run `./mvnw spotless:apply` before every commit — the build fails on formatting drift.
- Every source file needs the Apache 2.0 license header (copy from any neighboring file in the same module).
- The pom version stays `0.8.0-SNAPSHOT`. Never change it.
- Unit tests run under surefire (`*Test.java`); integration tests run under failsafe (`*IT.java`). Backend `*IT` classes need Docker, except Hazelcast which runs embedded.
- Useful commands:
  - One module's unit tests: `./mvnw -o -pl substrate-core -am test`
  - One module's ITs: `./mvnw -o -pl substrate-redis -am verify -Dit.test=RedisAtomIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
  - Everything: `./mvnw verify`

---

## File Structure

**Created:**
- `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/CasResult.java` — the three-valued SPI outcome.
- `substrate-hazelcast/src/main/java/org/jwcarman/substrate/hazelcast/atom/SetProcessor.java` — atomic value+TTL write.
- `substrate-hazelcast/src/main/java/org/jwcarman/substrate/hazelcast/atom/CompareAndSetProcessor.java` — atomic compare+write+TTL.

**Deleted:**
- `substrate-redis/src/main/java/org/jwcarman/substrate/redis/atom/AtomPayload.java` — superseded by a Redis hash.
- `substrate-redis/src/test/java/org/jwcarman/substrate/redis/atom/AtomPayloadTest.java` — if it exists.

**Modified:** `Atom.java` (api), `AtomSpi.java` / `DefaultAtom.java` / `DefaultAtomFactory.java` / `InMemoryAtomSpi.java` (core), and one `*AtomSpi.java` per backend module plus their tests.

---

### Task 1: Token becomes a per-write nonce

**Files:**
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtom.java:67,180-196`
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtomFactory.java:50,62`
- Test: `substrate-core/src/test/java/org/jwcarman/substrate/core/atom/DefaultAtomTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `static String DefaultAtom.nextToken()` — replaces `static String DefaultAtom.token(byte[])`, which is deleted. Tasks 3+ never call it; only core does.

**Context:** `DefaultAtom.token(byte[])` returns the first 128 bits of SHA-256 over the encoded value. That makes A→B→A return the original token (ABA), and makes re-setting an identical value a silent no-op that never notifies subscribers. Both make it unusable as a CAS basis.

- [ ] **Step 1: Rewrite the three token tests to describe nonce behavior**

In `DefaultAtomTest.java`, **delete** `tokenIsContentDerived()` and `identicalEncodedBytesProduceIdenticalTokens()` (around lines 431-448), and **replace** `setWithSameValueProducesSameToken()` (around line 450) with these three tests:

```java
  @Test
  void nextTokenProducesDistinctValues() {
    assertThat(DefaultAtom.nextToken()).isNotEqualTo(DefaultAtom.nextToken());
  }

  @Test
  void nextTokenIsTwentyTwoCharBase64Url() {
    assertThat(DefaultAtom.nextToken()).hasSize(22).matches("[A-Za-z0-9_-]{22}");
  }

  @Test
  void setWithSameValueProducesNewToken() {
    Snapshot<String> before = atom.get();

    atom.set("initial", TTL);

    Snapshot<String> after = atom.get();
    assertThat(after.value()).isEqualTo("initial");
    assertThat(after.token()).isNotEqualTo(before.token());
  }
```

Also replace the three `DefaultAtom.token(bytes)` setup calls (around lines 82, 260, 364) with `DefaultAtom.nextToken()`. The `bytes` local stays — it is still passed to `spi.create(...)`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -o -pl substrate-core -am test -Dtest=DefaultAtomTest`
Expected: FAIL — compilation error, `cannot find symbol: method nextToken()`.

- [ ] **Step 3: Replace the token generator**

In `DefaultAtom.java`, replace the `token(byte[])` method and its javadoc (lines ~180-196) with:

```java
  private static final int TOKEN_BYTES = 16;

  /**
   * Generates a fresh staleness token for a write. Each call returns a distinct 128-bit random
   * value, so a token identifies the <em>write</em> that produced it rather than the value it
   * wrote. That is what makes it sound to compare in {@link #compareAndSet}: a value that changes
   * from A to B and back to A does not resurrect its original token.
   */
  static String nextToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    ThreadLocalRandom.current().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
```

Update the call site at line 67 from `String newToken = token(bytes);` to `String newToken = nextToken();`.

Fix imports: add `java.util.concurrent.ThreadLocalRandom`; remove `java.security.MessageDigest`, `java.security.NoSuchAlgorithmException`, and `java.util.Arrays` **only if** nothing else in the file uses them (check `Arrays` — grep the file before removing).

In `DefaultAtomFactory.java`, change both `String token = DefaultAtom.token(bytes);` (lines 50 and 62) to `String token = DefaultAtom.nextToken();`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -o -pl substrate-core -am test`
Expected: PASS. This runs the whole core suite, not just `DefaultAtomTest` — subscription tests may have assumed identical writes produce no notification. If any fail, they are asserting the behavior this task deliberately changes: update the assertion to expect a delivery, and do not weaken the notification path to make them pass.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtom.java \
        substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtomFactory.java \
        substrate-core/src/test/java/org/jwcarman/substrate/core/atom/DefaultAtomTest.java
git commit -m "feat!: make the Atom snapshot token a per-write nonce

The token was the first 128 bits of SHA-256 over the encoded value, which
made it a value identity rather than a write identity: A->B->A restored the
original token, and re-setting an identical value produced no token change
and therefore no subscriber notification.

BREAKING CHANGE: set() with an unchanged value now moves the token and
notifies subscribers."
```

---

### Task 2: Add the `CasResult` enum and the SPI method

**Files:**
- Create: `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/CasResult.java`
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/AtomSpi.java`
- Modify (stub only): the nine `*AtomSpi.java` files listed in Step 3.

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum CasResult { COMMITTED, TOKEN_MISMATCH, ABSENT }` in `org.jwcarman.substrate.core.atom`.
  - `CasResult AtomSpi.compareAndSet(String key, String expectedToken, byte[] value, String newToken, Duration ttl)` — every later task implements exactly this signature.

**Context:** Adding an abstract method to `AtomSpi` breaks compilation in all nine backends at once. This task adds the contract plus throwing stubs so the build stays green; Tasks 3 and 5-12 each replace one stub. Task 13 verifies none survive.

- [ ] **Step 1: Create the enum**

Create `CasResult.java` (with the standard Apache 2.0 header copied from `AtomSpi.java`):

```java
package org.jwcarman.substrate.core.atom;

/**
 * Outcome of a conditional {@link AtomSpi#compareAndSet compareAndSet} write.
 *
 * <p>{@link #TOKEN_MISMATCH} and {@link #ABSENT} are kept distinct because they call for opposite
 * caller behavior: a mismatch is retryable, while an absent atom is terminal and would make a
 * retry loop spin forever.
 */
public enum CasResult {

  /** The expected token matched and the new value and TTL were written. */
  COMMITTED,

  /** A live atom exists at the key, but its token differs from the expected token. */
  TOKEN_MISMATCH,

  /** No live atom exists at the key — it has expired or been deleted. */
  ABSENT
}
```

- [ ] **Step 2: Add the SPI method**

In `AtomSpi.java`, add after `set(...)` (line ~67):

```java
  /**
   * Writes a new value and token only if the atom's current token equals {@code expectedToken},
   * resetting its TTL on success. Implementations must perform the comparison and the write as one
   * atomic operation — no lost update may be possible under concurrent writers.
   *
   * @param key the backend storage key
   * @param expectedToken the token the caller last observed
   * @param value the new serialized payload
   * @param newToken the new opaque staleness marker
   * @param ttl the new time-to-live
   * @return {@link CasResult#COMMITTED} if written, {@link CasResult#TOKEN_MISMATCH} if a live atom
   *     exists with a different token, {@link CasResult#ABSENT} if no live atom exists
   */
  CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl);
```

- [ ] **Step 3: Add a throwing stub to all nine backends**

Add this method to each of the nine SPI classes below, importing `org.jwcarman.substrate.core.atom.CasResult` (the in-memory and core classes are already in that package and need no import):

```java
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    throw new UnsupportedOperationException("compareAndSet not yet implemented");
  }
```

Files:
1. `substrate-core/src/main/java/org/jwcarman/substrate/core/memory/atom/InMemoryAtomSpi.java`
2. `substrate-postgresql/src/main/java/org/jwcarman/substrate/postgresql/atom/PostgresAtomSpi.java`
3. `substrate-mongodb/src/main/java/org/jwcarman/substrate/mongodb/atom/MongoDbAtomSpi.java`
4. `substrate-dynamodb/src/main/java/org/jwcarman/substrate/dynamodb/atom/DynamoDbAtomSpi.java`
5. `substrate-cassandra/src/main/java/org/jwcarman/substrate/cassandra/atom/CassandraAtomSpi.java`
6. `substrate-hazelcast/src/main/java/org/jwcarman/substrate/hazelcast/atom/HazelcastAtomSpi.java`
7. `substrate-redis/src/main/java/org/jwcarman/substrate/redis/atom/RedisAtomSpi.java`
8. `substrate-etcd/src/main/java/org/jwcarman/substrate/etcd/atom/EtcdAtomSpi.java`
9. `substrate-nats/src/main/java/org/jwcarman/substrate/nats/atom/NatsAtomSpi.java`

- [ ] **Step 4: Verify the whole project still compiles**

Run: `./mvnw -o -q -DskipTests package`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-core substrate-postgresql substrate-mongodb substrate-dynamodb \
        substrate-cassandra substrate-hazelcast substrate-redis substrate-etcd substrate-nats
git commit -m "feat: add AtomSpi.compareAndSet contract and CasResult

Backends carry throwing stubs; each is implemented in a following commit."
```

---

### Task 3: Implement `compareAndSet` for the in-memory backend

**Files:**
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/memory/atom/InMemoryAtomSpi.java`
- Test: `substrate-core/src/test/java/org/jwcarman/substrate/core/memory/atom/InMemoryAtomSpiTest.java`

**Interfaces:**
- Consumes: `CasResult` and the `AtomSpi.compareAndSet` signature from Task 2.
- Produces: the reference semantics every backend task must match.

- [ ] **Step 1: Write the failing tests**

Append to `InMemoryAtomSpiTest.java` (match the existing imports; add `org.jwcarman.substrate.core.atom.CasResult` and `java.nio.charset.StandardCharsets` if absent):

```java
  @Test
  void compareAndSetCommitsWhenTokenMatches() {
    String key = spi.atomKey("cas-match");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));

    CasResult result =
        spi.compareAndSet(
            key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.COMMITTED);
    assertThat(spi.read(key)).hasValueSatisfying(raw -> {
      assertThat(raw.value()).isEqualTo("v2".getBytes(StandardCharsets.UTF_8));
      assertThat(raw.token()).isEqualTo("tok-2");
    });
  }

  @Test
  void compareAndSetReportsMismatchAndLeavesValueUntouched() {
    String key = spi.atomKey("cas-mismatch");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));

    CasResult result =
        spi.compareAndSet(
            key, "stale", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.TOKEN_MISMATCH);
    assertThat(spi.read(key)).hasValueSatisfying(raw -> {
      assertThat(raw.value()).isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
      assertThat(raw.token()).isEqualTo("tok-1");
    });
  }

  @Test
  void compareAndSetReportsAbsentForDeletedAtom() {
    String key = spi.atomKey("cas-deleted");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    spi.delete(key);

    CasResult result =
        spi.compareAndSet(
            key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.ABSENT);
  }

  @Test
  void compareAndSetReportsAbsentForExpiredAtom() throws InterruptedException {
    String key = spi.atomKey("cas-expired");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMillis(50));
    Thread.sleep(120);

    CasResult result =
        spi.compareAndSet(
            key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.ABSENT);
  }

  @Test
  void compareAndSetResetsTtl() {
    String key = spi.atomKey("cas-ttl");
    spi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMillis(100));

    spi.compareAndSet(
        key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(spi.exists(key)).isTrue();
  }

  @Test
  void concurrentCompareAndSetAdmitsExactlyOneWinner() throws InterruptedException {
    String key = spi.atomKey("cas-race");
    spi.create(key, "v0".getBytes(StandardCharsets.UTF_8), "tok-0", Duration.ofMinutes(5));

    int threads = 16;
    var latch = new java.util.concurrent.CountDownLatch(1);
    var committed = new java.util.concurrent.atomic.AtomicInteger();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      int n = i;
      pool.submit(() -> {
        latch.await();
        // n + 1, not n: a new token of "tok-0" would equal the baseline every
        // thread is racing against, letting a second thread win legitimately.
        if (spi.compareAndSet(
                key, "tok-0", ("v" + n).getBytes(StandardCharsets.UTF_8),
                "tok-" + (n + 1), Duration.ofMinutes(5))
            == CasResult.COMMITTED) {
          committed.incrementAndGet();
        }
        return null;
      });
    }
    latch.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    assertThat(committed.get()).isEqualTo(1);
  }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-core -am test -Dtest=InMemoryAtomSpiTest`
Expected: FAIL — `UnsupportedOperationException: compareAndSet not yet implemented`.

- [ ] **Step 3: Implement**

Replace the stub in `InMemoryAtomSpi.java` with:

```java
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    ExpiringEntry<RawAtom> next =
        new ExpiringEntry<>(new RawAtom(value, newToken), Instant.now().plus(ttl));
    var outcome = new java.util.concurrent.atomic.AtomicReference<>(CasResult.ABSENT);
    store.compute(
        key,
        (k, existing) -> {
          if (existing == null || existing.isExpired()) {
            outcome.set(CasResult.ABSENT);
            return null;
          }
          if (!existing.value().token().equals(expectedToken)) {
            outcome.set(CasResult.TOKEN_MISMATCH);
            return existing;
          }
          outcome.set(CasResult.COMMITTED);
          return next;
        });
    return outcome.get();
  }
```

Add the import `java.util.concurrent.atomic.AtomicReference` and use the short name (no star imports; the fully-qualified names above are for clarity in this plan — hoist every one of them into an import statement before committing, in both the test and the implementation).

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -o -pl substrate-core -am test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-core
git commit -m "feat: implement compareAndSet for the in-memory Atom backend"
```

---

### Task 4: Expose `compareAndSet` on the public `Atom` API

**Files:**
- Modify: `substrate-api/src/main/java/org/jwcarman/substrate/atom/Atom.java:86` (insert after `set`)
- Modify: `substrate-core/src/main/java/org/jwcarman/substrate/core/atom/DefaultAtom.java`
- Test: `substrate-core/src/test/java/org/jwcarman/substrate/core/atom/DefaultAtomTest.java`

**Interfaces:**
- Consumes: `CasResult` (Task 2), `InMemoryAtomSpi.compareAndSet` (Task 3), `DefaultAtom.nextToken()` (Task 1).
- Produces: `boolean Atom.compareAndSet(Snapshot<T> expected, T data, Duration ttl)` — the public API. No later task depends on it.

- [ ] **Step 1: Write the failing tests**

Append to `DefaultAtomTest.java`:

```java
  @Test
  void compareAndSetCommitsWhenTokenMatches() {
    Snapshot<String> current = atom.get();

    boolean committed = atom.compareAndSet(current, "updated", TTL);

    assertThat(committed).isTrue();
    assertThat(atom.get().value()).isEqualTo("updated");
    assertThat(atom.get().token()).isNotEqualTo(current.token());
  }

  @Test
  void compareAndSetReturnsFalseOnStaleToken() {
    Snapshot<String> stale = atom.get();
    atom.set("winner", TTL);

    boolean committed = atom.compareAndSet(stale, "loser", TTL);

    assertThat(committed).isFalse();
    assertThat(atom.get().value()).isEqualTo("winner");
  }

  @Test
  void compareAndSetNotifiesSubscribersOnlyWhenCommitted() throws Exception {
    Snapshot<String> current = atom.get();
    var delivered = new java.util.concurrent.CopyOnWriteArrayList<String>();
    try (var sub = atom.subscribe(current, cfg -> cfg.onNext(s -> delivered.add(s.value())))) {
      assertThat(atom.compareAndSet(current, "committed", TTL)).isTrue();
      await().atMost(Duration.ofSeconds(5)).until(() -> delivered.contains("committed"));

      int deliveredCount = delivered.size();
      assertThat(atom.compareAndSet(current, "rejected", TTL)).isFalse();
      Thread.sleep(300);
      assertThat(delivered).hasSize(deliveredCount);
    }
  }

  @Test
  void compareAndSetThrowsOnDeadAtom() {
    Snapshot<String> current = atom.get();
    atom.delete();

    assertThatThrownBy(() -> atom.compareAndSet(current, "value", TTL))
        .isInstanceOf(AtomExpiredException.class);
  }

  @Test
  void compareAndSetRejectsNullExpected() {
    assertThatThrownBy(() -> atom.compareAndSet(null, "value", TTL))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void compareAndSetThrowsWhenTtlExceedsMaxTtl() {
    Snapshot<String> current = atom.get();

    assertThatThrownBy(() -> atom.compareAndSet(current, "value", Duration.ofHours(25)))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

Match the file's existing subscription-test idiom for `subscribe`/cancel — if `Subscription` is not `AutoCloseable` there, use an explicit `sub.cancel()` in a `finally` block instead of try-with-resources. Hoist `CopyOnWriteArrayList` into an import.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-core -am test -Dtest=DefaultAtomTest`
Expected: FAIL — `cannot find symbol: method compareAndSet`.

- [ ] **Step 3: Add the API method**

In `Atom.java`, insert directly after `set(T data, Duration ttl)`:

```java
  /**
   * Atomically sets the value of this atom only if its current token matches the one carried by
   * {@code expected}. On success the TTL is reset and subscribers are notified; on failure nothing
   * is written and no notification is sent.
   *
   * <p>A {@code false} return is <em>retryable</em> — another writer won the race, so re-read and
   * try again. {@link AtomExpiredException} is <em>terminal</em> — the atom is gone and retrying
   * cannot succeed.
   *
   * <pre>{@code
   * Snapshot<Session> current = atom.get();
   * Session next = current.value().withCount(current.value().count() + 1);
   * while (!atom.compareAndSet(current, next, Duration.ofHours(1))) {
   *   current = atom.get();
   *   next = current.value().withCount(current.value().count() + 1);
   * }
   * }</pre>
   *
   * @param expected the snapshot this write is conditioned on
   * @param data the new value to store
   * @param ttl the new time-to-live for this atom
   * @return {@code true} if the write was committed, {@code false} if another writer changed the
   *     atom first
   * @throws AtomExpiredException if the atom's lease has already elapsed or it has been deleted
   * @throws NullPointerException if {@code expected} is {@code null}
   * @throws IllegalArgumentException if {@code ttl} exceeds the maximum allowed duration
   */
  boolean compareAndSet(Snapshot<T> expected, T data, Duration ttl);
```

- [ ] **Step 4: Implement in `DefaultAtom`**

Insert after `set(...)` (line ~73):

```java
  @Override
  public boolean compareAndSet(Snapshot<T> expected, T data, Duration ttl) {
    Objects.requireNonNull(expected, "expected");
    ensureExists();
    validateTtl(ttl);
    byte[] bytes = codec.encode(data);
    CasResult result =
        context
            .spi()
            .compareAndSet(
                key, expected.token(), context.transformer().encode(bytes), nextToken(), ttl);
    return switch (result) {
      case COMMITTED -> {
        context.notifier().notifyAtomChanged(key);
        yield true;
      }
      case TOKEN_MISMATCH -> false;
      case ABSENT -> throw new AtomExpiredException(key);
    };
  }
```

Add the import `java.util.Objects`.

- [ ] **Step 5: Run to verify pass**

Run: `./mvnw -o -pl substrate-core -am test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-api substrate-core
git commit -m "feat: add Atom.compareAndSet

Conditional write keyed on the Snapshot token. Returns false on a lost race
and throws AtomExpiredException when the atom is gone, so retry loops cannot
spin against a dead atom."
```

---

### Task 5: PostgreSQL

**Files:**
- Modify: `substrate-postgresql/src/main/java/org/jwcarman/substrate/postgresql/atom/PostgresAtomSpi.java`
- Test: `substrate-postgresql/src/test/java/org/jwcarman/substrate/postgresql/atom/PostgresAtomIT.java`

**Interfaces:**
- Consumes: `CasResult`, the `AtomSpi.compareAndSet` signature.
- Produces: nothing consumed elsewhere.

**Context:** `token` is its own column. A single CTE statement reads the current token and performs the guarded update on the same MVCC snapshot, so one round-trip yields all three outcomes.

- [ ] **Step 1: Write the failing tests**

Add the four cases to `PostgresAtomIT.java`, following the file's existing style (it uses `spi`/`atom` as the field name — match whichever it uses):

```java
  @Test
  void compareAndSetCommitsWhenTokenMatches() {
    String key = atom.atomKey("cas-match-" + System.nanoTime());
    atom.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));

    CasResult result =
        atom.compareAndSet(
            key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.COMMITTED);
    assertThat(atom.read(key)).hasValueSatisfying(raw ->
        assertThat(raw.token()).isEqualTo("tok-2"));
  }

  @Test
  void compareAndSetReportsMismatchAndLeavesValueUntouched() {
    String key = atom.atomKey("cas-mismatch-" + System.nanoTime());
    atom.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));

    CasResult result =
        atom.compareAndSet(
            key, "stale", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    assertThat(result).isEqualTo(CasResult.TOKEN_MISMATCH);
    assertThat(atom.read(key)).hasValueSatisfying(raw -> {
      assertThat(raw.value()).isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
      assertThat(raw.token()).isEqualTo("tok-1");
    });
  }

  @Test
  void compareAndSetReportsAbsentForDeletedAtom() {
    String key = atom.atomKey("cas-deleted-" + System.nanoTime());
    atom.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    atom.delete(key);

    assertThat(
            atom.compareAndSet(
                key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2",
                Duration.ofMinutes(5)))
        .isEqualTo(CasResult.ABSENT);
  }

  @Test
  void compareAndSetReportsAbsentForNeverCreatedAtom() {
    String key = atom.atomKey("cas-missing-" + System.nanoTime());

    assertThat(
            atom.compareAndSet(
                key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2",
                Duration.ofMinutes(5)))
        .isEqualTo(CasResult.ABSENT);
  }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-postgresql -am verify -Dit.test=PostgresAtomIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement**

Replace the stub in `PostgresAtomSpi.java`:

```java
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "WITH current AS ("
                + " SELECT token FROM substrate_atom WHERE key = ? AND expires_at > NOW()"
                + "), updated AS ("
                + " UPDATE substrate_atom SET value = ?, token = ?, expires_at = ?"
                + " WHERE key = ? AND expires_at > NOW() AND token = ?"
                + " RETURNING 1"
                + ") SELECT (SELECT COUNT(*) FROM updated) AS updated_count,"
                + " (SELECT COUNT(*) FROM current) AS present_count",
            key,
            value,
            newToken,
            Timestamp.from(Instant.now().plus(ttl)),
            key,
            expectedToken);
    if (((Number) row.get("updated_count")).longValue() > 0) {
      return CasResult.COMMITTED;
    }
    return ((Number) row.get("present_count")).longValue() > 0
        ? CasResult.TOKEN_MISMATCH
        : CasResult.ABSENT;
  }
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -o -pl substrate-postgresql -am verify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-postgresql
git commit -m "feat: implement compareAndSet for the PostgreSQL Atom backend"
```

---

### Task 6: MongoDB

**Files:**
- Modify: `substrate-mongodb/src/main/java/org/jwcarman/substrate/mongodb/atom/MongoDbAtomSpi.java`
- Test: `substrate-mongodb/src/test/java/org/jwcarman/substrate/mongodb/atom/MongoDbAtomSpiIT.java`

**Context:** An aggregation-pipeline update wraps each `$set` field in a `$cond` on the token. All `$cond`s in a single `$set` stage evaluate against the *input* document, so a mismatch leaves every field unchanged. Returning the BEFORE document classifies the outcome without a second query.

- [ ] **Step 1: Write the failing tests**

Add the same four tests as Task 5 Step 1 to `MongoDbAtomSpiIT.java`, adjusting the SPI field name to match that file.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-mongodb -am verify -Dit.test=MongoDbAtomSpiIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement**

Replace the stub in `MongoDbAtomSpi.java`:

```java
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    Date now = Date.from(Instant.now());
    Date newExpireAt = Date.from(Instant.now().plus(ttl));
    Document filter =
        new Document(FIELD_KEY, key).append(FIELD_EXPIRE_AT, new Document("$gt", now));
    Document matches = new Document("$eq", List.of("$" + FIELD_TOKEN, expectedToken));
    List<Document> pipeline =
        List.of(
            new Document(
                "$set",
                new Document()
                    .append(
                        FIELD_VALUE,
                        new Document(
                            "$cond",
                            List.of(matches, new Binary(value), "$" + FIELD_VALUE)))
                    .append(
                        FIELD_TOKEN,
                        new Document("$cond", List.of(matches, newToken, "$" + FIELD_TOKEN)))
                    .append(
                        FIELD_EXPIRE_AT,
                        new Document(
                            "$cond", List.of(matches, newExpireAt, "$" + FIELD_EXPIRE_AT)))));

    Document before =
        mongoTemplate
            .getCollection(collectionName)
            .findOneAndUpdate(
                filter,
                pipeline,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));

    if (before == null) {
      return CasResult.ABSENT;
    }
    return expectedToken.equals(before.getString(FIELD_TOKEN))
        ? CasResult.COMMITTED
        : CasResult.TOKEN_MISMATCH;
  }
```

Add imports: `com.mongodb.client.model.FindOneAndUpdateOptions`, `com.mongodb.client.model.ReturnDocument`, `java.util.Date`, `java.util.List`.

**Verify while implementing:** existing documents store `expireAt` via `MongoTemplate`, which converts `Instant` to a BSON date. This method writes through the raw collection driver, which does not apply that conversion — hence `Date`. If `compareAndSetResetsTtl`-style behavior misbehaves, confirm the stored BSON type of `expireAt` before and after a CAS and make them match.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -o -pl substrate-mongodb -am verify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-mongodb
git commit -m "feat: implement compareAndSet for the MongoDB Atom backend"
```

---

### Task 7: DynamoDB

**Files:**
- Modify: `substrate-dynamodb/src/main/java/org/jwcarman/substrate/dynamodb/atom/DynamoDbAtomSpi.java`
- Test: `substrate-dynamodb/src/test/java/org/jwcarman/substrate/dynamodb/atom/DynamoDbAtomIT.java`

**Context:** DynamoDB reaps TTL lazily, so the existing `#t > :now` guard stays and the failure classifier must re-check the old item's TTL.

- [ ] **Step 1: Write the failing tests**

Add the same four tests as Task 5 Step 1 to `DynamoDbAtomIT.java`, adjusting the SPI field name.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-dynamodb -am verify -Dit.test=DynamoDbAtomIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement**

```java
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    long expiresAt = Instant.now().plus(ttl).getEpochSecond();
    long now = Instant.now().getEpochSecond();
    try {
      client.putItem(
          PutItemRequest.builder()
              .tableName(tableName)
              .item(
                  Map.of(
                      FIELD_PK, AttributeValue.builder().s(key).build(),
                      FIELD_VALUE,
                          AttributeValue.builder().b(SdkBytes.fromByteArray(value)).build(),
                      FIELD_TOKEN, AttributeValue.builder().s(newToken).build(),
                      FIELD_TTL, AttributeValue.builder().n(Long.toString(expiresAt)).build()))
              .conditionExpression("attribute_exists(pk) AND #t > :now AND #tok = :expected")
              .expressionAttributeNames(Map.of("#t", FIELD_TTL, "#tok", FIELD_TOKEN))
              .expressionAttributeValues(
                  Map.of(
                      ":now", AttributeValue.builder().n(Long.toString(now)).build(),
                      ":expected", AttributeValue.builder().s(expectedToken).build()))
              .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
              .build());
      return CasResult.COMMITTED;
    } catch (ConditionalCheckFailedException e) {
      Map<String, AttributeValue> old = e.item();
      if (old == null || old.isEmpty()) {
        return CasResult.ABSENT;
      }
      long oldTtl = Long.parseLong(old.get(FIELD_TTL).n());
      return Instant.now().getEpochSecond() >= oldTtl ? CasResult.ABSENT : CasResult.TOKEN_MISMATCH;
    }
  }
```

Add the import `software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure`.

**Verify while implementing:** confirm the DynamoDB Local image used by the IT populates `ConditionalCheckFailedException.item()`. If it returns empty, every mismatch would misreport as `ABSENT` and `compareAndSetReportsMismatchAndLeavesValueUntouched` will fail — in that case fall back to a `getItem` in the catch block to classify, and add a comment saying why.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -o -pl substrate-dynamodb -am verify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-dynamodb
git commit -m "feat: implement compareAndSet for the DynamoDB Atom backend"
```

---

### Task 8: Cassandra

**Files:**
- Modify: `substrate-cassandra/src/main/java/org/jwcarman/substrate/cassandra/atom/CassandraAtomSpi.java`
- Test: `substrate-cassandra/src/test/java/org/jwcarman/substrate/cassandra/atom/CassandraAtomSpiIT.java`

**Context:** The `updateIfToken` prepared statement already exists — `UPDATE ... USING TTL ? SET value = ?, token = ? WHERE key = ? IF token = ?` — currently used by `touch()` to re-write the same value. CAS binds a *new* value and token instead. On `[applied] = false` the LWT result row carries the current `token` column; a null token means no row exists.

- [ ] **Step 1: Write the failing tests**

Add the same four tests as Task 5 Step 1 to `CassandraAtomSpiIT.java`, adjusting the SPI field name.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-cassandra -am verify -Dit.test=CassandraAtomSpiIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement**

```java
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    Row row =
        session
            .execute(
                updateIfToken.bind(
                    ttlSeconds(ttl), ByteBuffer.wrap(value), newToken, key, expectedToken))
            .one();
    if (row == null) {
      return CasResult.ABSENT;
    }
    if (row.getBoolean(APPLIED_COLUMN)) {
      return CasResult.COMMITTED;
    }
    // An LWT against a nonexistent row returns ONLY the [applied] column -- no token
    // column at all -- so getString(FIELD_TOKEN) throws rather than returning null.
    if (!row.getColumnDefinitions().contains(FIELD_TOKEN)) {
      return CasResult.ABSENT;
    }
    return row.getString(FIELD_TOKEN) == null ? CasResult.ABSENT : CasResult.TOKEN_MISMATCH;
  }
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -o -pl substrate-cassandra -am verify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-cassandra
git commit -m "feat: implement compareAndSet for the Cassandra Atom backend"
```

---

### Task 9: Hazelcast — `EntryProcessor` and the TTL bug fix

**Files:**
- Create: `substrate-hazelcast/src/main/java/org/jwcarman/substrate/hazelcast/atom/SetProcessor.java`
- Create: `substrate-hazelcast/src/main/java/org/jwcarman/substrate/hazelcast/atom/CompareAndSetProcessor.java`
- Modify: `substrate-hazelcast/src/main/java/org/jwcarman/substrate/hazelcast/atom/HazelcastAtomSpi.java:55-62`
- Test: `substrate-hazelcast/src/test/java/org/jwcarman/substrate/hazelcast/atom/HazelcastAtomIT.java`

**Context — this task fixes a confirmed bug.** `IMap.replace(key, value)` discards the entry's per-entry TTL, setting expiration to `Long.MAX_VALUE`. Verified by probe:

```
after putIfAbsent(ttl=60s) : 1785674609000
after replace(key, value)  : 9223372036854775807   (Long.MAX_VALUE)
after setTtl(60s)          : 1785674609000
```

So today's `set()` leaves the atom immortal between its `replace` and `setTtl` calls. An interruption in that window produces an atom that never expires — and `HazelcastAtomSpi` inherits the no-op `sweep()`, so nothing reclaims it. An `EntryProcessor` runs on the partition thread with the key locked and applies value and TTL in one operation, fixing `set` and delivering `compareAndSet` together.

- [ ] **Step 1: Write the failing tests**

Add the four CAS tests from Task 5 Step 1 to `HazelcastAtomIT.java` (it uses `spi` as the field name), plus this regression test for the bug:

```java
  @Test
  void setNeverLeavesTheAtomWithoutAnExpiration() {
    String mapName = "substrate-atoms-ttl-" + System.nanoTime();
    HazelcastAtomSpi ttlSpi = new HazelcastAtomSpi(hazelcast, "substrate:atom:", mapName);
    String key = ttlSpi.atomKey("ttl-guard");

    ttlSpi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    ttlSpi.set(key, "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    long expiration =
        hazelcast.<String, AtomEntry>getMap(mapName).getEntryView(key).getExpirationTime();
    assertThat(expiration).isLessThan(Long.MAX_VALUE);
    assertThat(expiration).isCloseTo(
        System.currentTimeMillis() + Duration.ofMinutes(5).toMillis(),
        within(30_000L));
  }

  @Test
  void compareAndSetNeverLeavesTheAtomWithoutAnExpiration() {
    String mapName = "substrate-atoms-ttl-cas-" + System.nanoTime();
    HazelcastAtomSpi ttlSpi = new HazelcastAtomSpi(hazelcast, "substrate:atom:", mapName);
    String key = ttlSpi.atomKey("ttl-guard-cas");

    ttlSpi.create(key, "v1".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    ttlSpi.compareAndSet(
        key, "tok-1", "v2".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5));

    long expiration =
        hazelcast.<String, AtomEntry>getMap(mapName).getEntryView(key).getExpirationTime();
    assertThat(expiration).isLessThan(Long.MAX_VALUE);
  }
```

Add the static import `org.assertj.core.api.Assertions.within`.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-hazelcast -am verify -Dit.test=HazelcastAtomIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: the CAS tests FAIL with `UnsupportedOperationException`. `setNeverLeavesTheAtomWithoutAnExpiration` will likely PASS already, because today's `setTtl` repairs the window microseconds later — it is a guard against regression, not a reproduction. Do not weaken it to force a red; note the result and continue.

- [ ] **Step 3: Create `SetProcessor`**

```java
package org.jwcarman.substrate.hazelcast.atom;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Writes a new value and TTL to an existing atom as a single partition-local operation.
 *
 * <p>This exists because {@code IMap.replace(key, value)} discards the entry's per-entry TTL,
 * leaving the atom with no expiration until a follow-up {@code setTtl} repairs it. An interruption
 * in that window produces an atom that never expires and is never swept.
 */
public class SetProcessor implements EntryProcessor<String, AtomEntry, Boolean>, Serializable {

  private static final long serialVersionUID = 1L;

  private final byte[] value;
  private final String token;
  private final long ttlMillis;

  public SetProcessor(byte[] value, String token, long ttlMillis) {
    this.value = value;
    this.token = token;
    this.ttlMillis = ttlMillis;
  }

  @Override
  public Boolean process(Map.Entry<String, AtomEntry> entry) {
    if (entry.getValue() == null) {
      return false;
    }
    if (entry instanceof ExtendedMapEntry<String, AtomEntry> extended) {
      extended.setValue(new AtomEntry(value, token), ttlMillis, TimeUnit.MILLISECONDS);
      return true;
    }
    throw new IllegalStateException(
        "Hazelcast entry does not support per-entry TTL: " + entry.getClass());
  }
}
```

The `instanceof` pattern with matching type arguments is a checked narrowing, so it compiles without an unchecked warning — do not replace it with a plain cast, which would produce one.

- [ ] **Step 4: Create `CompareAndSetProcessor`**

```java
package org.jwcarman.substrate.hazelcast.atom;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.core.atom.CasResult;

/**
 * Compares an atom's token and, on a match, writes the new value and TTL — all as a single
 * partition-local operation, so no concurrent writer can interleave.
 */
public class CompareAndSetProcessor
    implements EntryProcessor<String, AtomEntry, CasResult>, Serializable {

  private static final long serialVersionUID = 1L;

  private final String expectedToken;
  private final byte[] value;
  private final String newToken;
  private final long ttlMillis;

  public CompareAndSetProcessor(
      String expectedToken, byte[] value, String newToken, long ttlMillis) {
    this.expectedToken = expectedToken;
    this.value = value;
    this.newToken = newToken;
    this.ttlMillis = ttlMillis;
  }

  @Override
  public CasResult process(Map.Entry<String, AtomEntry> entry) {
    AtomEntry current = entry.getValue();
    if (current == null) {
      return CasResult.ABSENT;
    }
    if (!current.token().equals(expectedToken)) {
      return CasResult.TOKEN_MISMATCH;
    }
    if (entry instanceof ExtendedMapEntry<String, AtomEntry> extended) {
      extended.setValue(new AtomEntry(value, newToken), ttlMillis, TimeUnit.MILLISECONDS);
      return CasResult.COMMITTED;
    }
    throw new IllegalStateException(
        "Hazelcast entry does not support per-entry TTL: " + entry.getClass());
  }
}
```

- [ ] **Step 5: Rewrite `set` and implement `compareAndSet`**

In `HazelcastAtomSpi.java`, replace the whole `set` method (lines 55-62) and the `compareAndSet` stub with:

```java
  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    return Boolean.TRUE.equals(
        map.executeOnKey(key, new SetProcessor(value, token, ttl.toMillis())));
  }

  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    return map.executeOnKey(
        key, new CompareAndSetProcessor(expectedToken, value, newToken, ttl.toMillis()));
  }
```

Remove the now-unused `java.util.concurrent.TimeUnit` import if `touch` is the only other user — check first; `touch` still calls `map.setTtl(...)` and needs it.

- [ ] **Step 6: Run to verify pass**

Run: `./mvnw -o -pl substrate-hazelcast -am verify`
Expected: PASS.

- [ ] **Step 7: Document the deployment requirement**

Add to the `HazelcastAtomSpi` class javadoc:

```java
/**
 * Atom storage backed by a Hazelcast {@code IMap}.
 *
 * <p><strong>Deployment note:</strong> {@link SetProcessor} and {@link CompareAndSetProcessor} run
 * on cluster members, so this module's classes must be present on every member's classpath. That
 * is automatic for embedded Hazelcast. Deployments that reach a remote cluster through a Hazelcast
 * client must either place the substrate jar on the members or enable user-code deployment.
 */
```

- [ ] **Step 8: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-hazelcast
git commit -m "fix: apply Hazelcast Atom value and TTL atomically; add compareAndSet

IMap.replace(key, value) discards the entry's per-entry TTL, setting the
expiration to Long.MAX_VALUE. set() therefore left the atom immortal between
its replace and setTtl calls; an interruption in that window produced an atom
that never expired and was never swept. Both set and compareAndSet now run as
EntryProcessors, which apply value and TTL in one partition-local operation.

Requires this module's classes on cluster-member classpaths in client-server
topologies."
```

---

### Task 10: Redis — hash storage and Lua CAS

**Files:**
- Modify: `substrate-redis/src/main/java/org/jwcarman/substrate/redis/atom/RedisAtomSpi.java`
- Delete: `substrate-redis/src/main/java/org/jwcarman/substrate/redis/atom/AtomPayload.java`
- Delete: `substrate-redis/src/test/java/org/jwcarman/substrate/redis/atom/AtomPayloadTest.java` (if present)
- Test: `substrate-redis/src/test/java/org/jwcarman/substrate/redis/atom/RedisAtomIT.java`

**Context:** `AtomPayload` packs `[4-byte token length][token][value]` and Base64-encodes the whole blob into one string. Lua has no Base64 decode, so a script cannot read the token. The entry becomes a Redis hash with separate `token` and `value` fields; the value stays Base64-encoded because the Lettuce connection uses a `String` codec. `create` and `set` also move into scripts so an entry can never exist without a TTL. This is a storage-format change with no migration path — Atom entries are ephemeral leased state.

- [ ] **Step 1: Write the failing tests**

Add the four CAS tests from Task 5 Step 1 to `RedisAtomIT.java` (it uses `atom` as the field name).

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-redis -am verify -Dit.test=RedisAtomIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Rewrite `RedisAtomSpi` for hash storage**

```java
package org.jwcarman.substrate.redis.atom;

import io.lettuce.core.ExpireArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.CasResult;
import org.jwcarman.substrate.core.atom.RawAtom;

/**
 * Atom storage backed by Redis. Each atom is a hash with a {@code token} field and a Base64-encoded
 * {@code value} field, with the key's TTL carrying the lease. Writes go through Lua scripts so the
 * field update and the {@code EXPIRE} are applied atomically — an atom can never exist without an
 * expiration — and so {@code compareAndSet} can compare the token server-side.
 */
public class RedisAtomSpi extends AbstractAtomSpi {

  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_VALUE = "value";

  private static final String CREATE_SCRIPT =
      "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
          + "redis.call('HSET', KEYS[1], 'token', ARGV[1], 'value', ARGV[2]) "
          + "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
          + "return 1";

  private static final String SET_SCRIPT =
      "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
          + "redis.call('HSET', KEYS[1], 'token', ARGV[1], 'value', ARGV[2]) "
          + "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
          + "return 1";

  private static final String CAS_SCRIPT =
      "local current = redis.call('HGET', KEYS[1], 'token') "
          + "if not current then return -1 end "
          + "if current ~= ARGV[1] then return 0 end "
          + "redis.call('HSET', KEYS[1], 'token', ARGV[2], 'value', ARGV[3]) "
          + "redis.call('EXPIRE', KEYS[1], ARGV[4]) "
          + "return 1";

  private final RedisCommands<String, String> commands;

  public RedisAtomSpi(RedisCommands<String, String> commands, String prefix) {
    super(prefix);
    this.commands = commands;
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    Long result =
        commands.eval(
            CREATE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {key},
            token,
            encode(value),
            seconds(ttl));
    if (result == null || result == 0L) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    Map<String, String> fields = commands.hgetall(key);
    if (fields.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new RawAtom(decode(fields.get(FIELD_VALUE)), fields.get(FIELD_TOKEN)));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    Long result =
        commands.eval(
            SET_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {key},
            token,
            encode(value),
            seconds(ttl));
    return result != null && result == 1L;
  }

  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    Long result =
        commands.eval(
            CAS_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {key},
            expectedToken,
            newToken,
            encode(value),
            seconds(ttl));
    if (result == null) {
      return CasResult.ABSENT;
    }
    return switch (result.intValue()) {
      case 1 -> CasResult.COMMITTED;
      case 0 -> CasResult.TOKEN_MISMATCH;
      default -> CasResult.ABSENT;
    };
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    return commands.expire(key, ttl.toSeconds(), ExpireArgs.Builder.xx());
  }

  @Override
  public void delete(String key) {
    commands.del(key);
  }

  @Override
  public boolean exists(String key) {
    return commands.exists(key) > 0;
  }

  private static String encode(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static byte[] decode(String encoded) {
    return Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
  }

  private static String seconds(Duration ttl) {
    return Long.toString(Math.max(1L, ttl.toSeconds()));
  }
}
```

Then delete `AtomPayload.java` and its test if one exists.

**Note on `seconds(...)`:** the old code passed `ttl.toSeconds()` straight to `SetArgs.ex(...)`, which fails for sub-second TTLs. The `Math.max(1L, ...)` floor matches what `CassandraAtomSpi.ttlSeconds` already does. If any existing Redis IT asserts sub-second expiry, it will now wait a full second — adjust the test's timing, not the floor.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -o -pl substrate-redis -am verify`
Expected: PASS — including the pre-existing round-trip, create-conflict, and TTL tests, which now exercise hash storage.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-redis
git commit -m "feat!: store Redis atoms as hashes and add compareAndSet

The packed Base64 payload could not be read from Lua, so the token could not
be compared server-side. Atoms are now hashes with separate token and value
fields, and all writes go through Lua so the field update and EXPIRE apply
atomically.

BREAKING CHANGE: the Redis Atom storage format changed. Atoms are ephemeral
leased state, so no migration path is provided."
```

---

### Task 11: etcd

**Files:**
- Modify: `substrate-etcd/src/main/java/org/jwcarman/substrate/etcd/atom/EtcdAtomSpi.java`
- Test: `substrate-etcd/src/test/java/org/jwcarman/substrate/etcd/atom/EtcdAtomSpiIT.java`

**Context:** The existing private `leasedWrite(Cmp, LongFunction<Op>, long, Duration, String)` helper runs a put-on-a-fresh-lease txn guarded by a comparison and handles lease cleanup. CAS reuses it with a `modRevision` comparison, which is the atomic guard: if any writer touched the key after our read, the revision moved and the txn does not apply.

- [ ] **Step 1: Write the failing tests**

Add the same four tests as Task 5 Step 1 to `EtcdAtomSpiIT.java`, adjusting the SPI field name.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-etcd -am verify -Dit.test=EtcdAtomSpiIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement**

```java
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    ByteSequence keyBs = bs(key);
    KeyValue current = currentKv(keyBs);
    if (current == null) {
      return CasResult.ABSENT;
    }
    RawAtom raw = AtomPayload.decode(current.getValue().getBytes());
    if (!raw.token().equals(expectedToken)) {
      return CasResult.TOKEN_MISMATCH;
    }
    boolean applied =
        leasedWrite(
            new Cmp(keyBs, Cmp.Op.EQUAL, CmpTarget.modRevision(current.getModRevision())),
            newLeaseId ->
                Op.put(keyBs, bs(AtomPayload.encode(value, newToken)), withLease(newLeaseId)),
            current.getLease(),
            ttl,
            "compare-and-set atom in etcd");
    if (applied) {
      return CasResult.COMMITTED;
    }
    return atomExists(keyBs) ? CasResult.TOKEN_MISMATCH : CasResult.ABSENT;
  }
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -o -pl substrate-etcd -am verify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-etcd
git commit -m "feat: implement compareAndSet for the etcd Atom backend"
```

---

### Task 12: NATS

**Files:**
- Modify: `substrate-nats/src/main/java/org/jwcarman/substrate/nats/atom/NatsAtomSpi.java`
- Test: `substrate-nats/src/test/java/org/jwcarman/substrate/nats/atom/NatsAtomIT.java`

**Context:** NATS KV has no transaction. `kv.update(key, value, expectedRevision)` is a genuine atomic CAS on revision, so the *write* is exact and no lost update is possible. Only the *classification* of a failure needs a follow-up read, and if the atom is deleted between the failed update and that read, a `TOKEN_MISMATCH` is reported as `ABSENT`. Both mean the caller lost, and `ABSENT` is true of the atom at the moment it is read, so this is acceptable — but it must be documented in the method javadoc.

- [ ] **Step 1: Write the failing tests**

Add the same four tests as Task 5 Step 1 to `NatsAtomIT.java`, adjusting the SPI field name.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -o -pl substrate-nats -am verify -Dit.test=NatsAtomIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement**

```java
  /**
   * {@inheritDoc}
   *
   * <p>NATS KV offers no transaction, so the outcome is determined in two parts. The write itself
   * is exact: {@code update} with the revision observed by the preceding read is an atomic
   * compare-and-swap, so no lost update is possible. Classifying a <em>failed</em> write requires a
   * follow-up read, and if the atom is deleted between the failed update and that read, the
   * failure is reported as {@link CasResult#ABSENT} rather than {@link CasResult#TOKEN_MISMATCH}.
   * Both outcomes mean the caller lost the race, and {@code ABSENT} is a true statement about the
   * atom at the moment it was read.
   */
  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    try {
      var kv = connection.keyValue(bucketName);
      KeyValueEntry entry = kv.get(toKvKey(key));
      if (entry == null || entry.getOperation() != KeyValueOperation.PUT) {
        return CasResult.ABSENT;
      }
      if (!decode(entry.getValue()).token().equals(expectedToken)) {
        return CasResult.TOKEN_MISMATCH;
      }
      try {
        kv.update(toKvKey(key), encode(value, newToken), entry.getRevision());
        return CasResult.COMMITTED;
      } catch (JetStreamApiException e) {
        if (!isWrongLastSequence(e)) {
          throw new IllegalStateException("Failed to compare-and-set atom in NATS KV", e);
        }
        KeyValueEntry latest = kv.get(toKvKey(key));
        return latest == null || latest.getOperation() != KeyValueOperation.PUT
            ? CasResult.ABSENT
            : CasResult.TOKEN_MISMATCH;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to compare-and-set atom in NATS KV", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to compare-and-set atom in NATS KV", e);
    }
  }

  private static boolean isWrongLastSequence(JetStreamApiException e) {
    return e.getApiErrorCode() == 10071;
  }
```

**Verify while implementing:** `10071` is the code the existing `isKeyExists` uses for a create conflict. Confirm the client raises the same code for a wrong-revision `update` — write a scratch assertion that performs a mismatched `update` and prints `e.getApiErrorCode()`, then set the constant to whatever it actually reports. Do not guess; a wrong code turns every mismatch into a thrown `IllegalStateException`.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -o -pl substrate-nats -am verify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw -o -q spotless:apply
git add substrate-nats
git commit -m "feat: implement compareAndSet for the NATS Atom backend"
```

---

### Task 13: Documentation, CHANGELOG, and final verification

**Files:**
- Modify: `README.md` (Atom section ~line 86; exception table ~line 413)
- Modify: `CHANGELOG.md` (the existing `## [Unreleased]` section)
- Modify: `substrate-api/src/main/java/org/jwcarman/substrate/atom/Snapshot.java`

- [ ] **Step 1: Confirm no stubs survived**

Run: `grep -rn "compareAndSet not yet implemented" --include=*.java .`
Expected: no output. If anything matches, that backend's task was skipped — go implement it.

- [ ] **Step 2: Update the `Snapshot` javadoc**

Replace the second paragraph of the class javadoc with:

```java
 * <p>The {@link #token()} is an opaque marker identifying the write that produced this value. Every
 * write generates a fresh token, so a token identifies a write rather than a value: setting the
 * same value twice yields two different tokens. Tokens should be compared only via {@link
 * Object#equals(Object)}; their internal format is an implementation detail.
 *
 * <p>Pass a {@code Snapshot} to {@link Atom#subscribe(Snapshot)} to receive notifications only for
 * changes after the state it represents, or to {@link Atom#compareAndSet} to make a write
 * conditional on nothing having changed since.
```

- [ ] **Step 3: Add a README example**

In the Atom section, after the existing `set`/`get` example:

````markdown
Writes are last-write-wins by default. For read-modify-write workflows, use
`compareAndSet`, which commits only if nobody else has written since your
snapshot:

```java
Snapshot<Session> current = session.get();
Session updated = current.value().withHitCount(current.value().hitCount() + 1);

while (!session.compareAndSet(current, updated, Duration.ofHours(1))) {
    current = session.get();
    updated = current.value().withHitCount(current.value().hitCount() + 1);
}
```

A `false` return means another writer won — re-read and retry. An
`AtomExpiredException` means the atom is gone and retrying cannot help.
````

In the exception table, change the `AtomExpiredException` row's trigger list from ``set` / `get` / `touch`` to ``set` / `compareAndSet` / `get` / `touch``.

- [ ] **Step 4: Update the CHANGELOG**

Add to the existing `## [Unreleased]` section, preserving the PostgreSQL entry already there:

```markdown
### Added

- `Atom.compareAndSet(Snapshot<T>, T, Duration)` — a conditional write that
  commits only when the atom's token still matches the given snapshot. Returns
  `false` when another writer won the race and throws `AtomExpiredException`
  when the atom is gone, so retry loops cannot spin against a dead atom.
- `AtomSpi.compareAndSet(...)` and `CasResult` for backend implementors.

### Fixed

- `substrate-hazelcast`: `set()` left the atom with no expiration between its
  `IMap.replace` and `setTtl` calls, because `replace` discards the entry's
  per-entry TTL. An interruption in that window produced an atom that never
  expired and was never swept. Both `set` and `compareAndSet` now apply value
  and TTL in one `EntryProcessor`.

### Breaking changes

- The `Snapshot` token is now a per-write random nonce rather than a hash of the
  value. As a result, `set()` with an unchanged value now moves the token and
  notifies subscribers, where previously it was silently invisible.
- `AtomSpi` gained an abstract `compareAndSet` method. Third-party `AtomSpi`
  implementations must implement it.
- `substrate-redis` changed its Atom storage format from a single packed Base64
  string to a hash with separate `token` and `value` fields. Atoms are ephemeral
  leased state, so no migration path is provided — existing atoms should be
  allowed to expire, or the keys flushed.
- `substrate-hazelcast` now runs `EntryProcessor`s on cluster members, so this
  module's classes must be on every member's classpath. This is automatic for
  embedded Hazelcast; client-server deployments need the substrate jar on the
  members or user-code deployment enabled.
```

- [ ] **Step 5: Full verification**

Run: `./mvnw verify`
Expected: BUILD SUCCESS across every module.

Run: `./mvnw -P release javadoc:jar -DskipTests`
Expected: BUILD SUCCESS. The release profile sets `failOnError=true` on javadoc, so missing `@param`/`@return` tags on the new public methods fail the Maven Central publish workflow but do **not** show up in a plain `verify`. Fix any doclint errors here.

- [ ] **Step 6: Commit**

```bash
./mvnw -o -q spotless:apply
git add README.md CHANGELOG.md substrate-api
git commit -m "docs: document Atom.compareAndSet and the 0.8.0 breaking changes"
```

---

## Self-Review

**Spec coverage:** §1 public API → Task 4. §2 token nonce → Task 1. §3 SPI + `CasResult` → Task 2. §4 backend table → Tasks 3, 5-12 (all nine). §4a Hazelcast bug → Task 9. §5 tests → per-task Step 1, plus the concurrency test in Task 3 and the TTL regression in Task 9. §6 documentation → Task 13. No gaps.

**Placeholder scan:** every code step carries real code. The three "verify while implementing" notes (Mongo BSON date type, DynamoDB Local's `ConditionalCheckFailedException.item()`, the NATS error code) are deliberate — each names the exact symptom, the exact check to run, and the exact fallback, rather than deferring a decision.

**Type consistency:** `CasResult.{COMMITTED, TOKEN_MISMATCH, ABSENT}` and the parameter order `(key, expectedToken, value, newToken, ttl)` are identical in Task 2's contract and all nine implementations. `DefaultAtom.nextToken()` is defined in Task 1 and used in Tasks 1 and 4 only. `Atom.compareAndSet(Snapshot<T>, T, Duration)` is defined once in Task 4. Hazelcast's two processors are named `SetProcessor` and `CompareAndSetProcessor` in both their creation steps and their call sites.
