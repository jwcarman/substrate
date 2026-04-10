# `MongoAtomSpi` — Atom backend for MongoDB

**Depends on: spec 018 (Atom primitive) must be completed first.**

## What to build

Add an `AtomSpi` implementation to the `substrate-mongodb` module
using MongoDB's unique index for atomic create and TTL index for
expiry.

## MongoDB primitives used

- **`insertOne`** with a unique index on the `_id` field — throws
  `MongoWriteException` with `DuplicateKeyException` on collision,
  which maps to `AtomAlreadyExistsException`.
- **`replaceOne` with `filter: { _id: key, expiresAt: { $gt: now } }`** —
  conditional replace that only succeeds on live atoms. Returns
  `modifiedCount=0` if the filter didn't match.
- **`updateOne` with `filter: { _id: key, expiresAt: { $gt: now } }`
  and `update: { $set: { expiresAt: ... } }`** — for `touch`,
  updates only the expiry.
- **`findOne`** — standard read. Filter explicitly on `expiresAt >
  now` because MongoDB's TTL sweep runs every ~60 seconds and
  expired documents may linger until then.
- **`deleteOne`** — idempotent removal.

MongoDB TTL index handles expiry natively. `MongoAtomSpi.sweep(int)`
inherits the no-op default.

## Collection schema

Collection name: configurable, default `substrate_atoms`.

Document shape:

```json
{
  "_id": "<backend-qualified-key>",
  "value": BinData,
  "token": "<base64-sha256>",
  "expiresAt": ISODate
}
```

Indexes required (created on first use if absent):

- **Primary key index** on `_id` (implicit, always exists).
- **TTL index** on `expiresAt` with `expireAfterSeconds: 0` —
  MongoDB removes documents when `expiresAt` is in the past, so
  the index is created with expireAfterSeconds=0 meaning "expire
  at exactly `expiresAt`." The sweeper runs about once per minute.

## Files created

```
substrate-mongodb/src/main/java/org/jwcarman/substrate/mongodb/atom/
  MongoAtomSpi.java
  MongoAtomAutoConfiguration.java

substrate-mongodb/src/test/java/org/jwcarman/substrate/mongodb/atom/
  MongoAtomSpiTest.java
  MongoAtomIT.java
```

## Files modified

- `substrate-mongodb/src/main/java/org/jwcarman/substrate/mongodb/MongoProperties.java` —
  add nested `AtomProperties(boolean enabled, String collectionName)`
  with defaults `(true, "substrate_atoms")`.
- `AutoConfiguration.imports` — register `MongoAtomAutoConfiguration`.
- `substrate-mongodb-defaults.properties` — document new properties.

## `MongoAtomSpi` sketch

```java
package org.jwcarman.substrate.mongodb.atom;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Updates.set;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.Binary;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.AtomRecord;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;

public class MongoAtomSpi extends AbstractAtomSpi {

  private final MongoCollection<Document> collection;

  public MongoAtomSpi(MongoClient client, String databaseName, String collectionName) {
    super("substrate:atom:");
    this.collection = client.getDatabase(databaseName).getCollection(collectionName);
    // TTL index: expireAfterSeconds=0 means "expire at the ISODate in this field"
    collection.createIndex(
        Indexes.ascending("expiresAt"),
        new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    Date expiresAt = Date.from(Instant.now().plus(ttl));
    Document doc = new Document("_id", key)
        .append("value", new Binary(value))
        .append("token", token)
        .append("expiresAt", expiresAt);
    try {
      collection.insertOne(doc);
    } catch (MongoWriteException e) {
      if (e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
        throw new AtomAlreadyExistsException(key);
      }
      throw e;
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    Document doc = collection.find(
        and(eq("_id", key), gt("expiresAt", new Date()))
    ).first();
    if (doc == null) return Optional.empty();
    byte[] bytes = doc.get("value", Binary.class).getData();
    String token = doc.getString("token");
    return Optional.of(new AtomRecord(bytes, token));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    Date expiresAt = Date.from(Instant.now().plus(ttl));
    Document replacement = new Document("_id", key)
        .append("value", new Binary(value))
        .append("token", token)
        .append("expiresAt", expiresAt);
    var result = collection.replaceOne(
        and(eq("_id", key), gt("expiresAt", new Date())),
        replacement);
    return result.getModifiedCount() > 0;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    Date expiresAt = Date.from(Instant.now().plus(ttl));
    UpdateResult result = collection.updateOne(
        and(eq("_id", key), gt("expiresAt", new Date())),
        set("expiresAt", expiresAt));
    return result.getModifiedCount() > 0;
  }

  @Override
  public void delete(String key) {
    collection.deleteOne(eq("_id", key));
  }
}
```

## Auto-configuration

```java
@AutoConfiguration
@ConditionalOnClass(MongoClient.class)
@ConditionalOnProperty(prefix = "substrate.mongodb.atom",
                       name = "enabled",
                       havingValue = "true",
                       matchIfMissing = true)
public class MongoAtomAutoConfiguration {

  @Bean
  @ConditionalOnBean(MongoClient.class)
  @ConditionalOnMissingBean(AtomSpi.class)
  public MongoAtomSpi mongoAtomSpi(MongoClient client, MongoProperties props) {
    return new MongoAtomSpi(client, props.databaseName(), props.atom().collectionName());
  }
}
```

## Acceptance criteria

- [ ] `MongoAtomSpi` implements all `AtomSpi` methods.
- [ ] Constructor creates the TTL index on `expiresAt` with
      `expireAfterSeconds: 0` if it doesn't already exist.
- [ ] `create` catches `MongoWriteException` with
      `DUPLICATE_KEY` error category and throws
      `AtomAlreadyExistsException`.
- [ ] `read` explicitly filters on `expiresAt > now()` in addition
      to `_id` matching, so expired documents that haven't been
      physically removed yet still read as absent.
- [ ] `set` and `touch` both filter on `expiresAt > now()` and
      return `true`/`false` based on `modifiedCount`.
- [ ] `delete` is idempotent.
- [ ] `sweep(int)` inherits the no-op from `AbstractAtomSpi`.
- [ ] `MongoProperties.atom` nested record with `enabled` and
      `collectionName` defaults.
- [ ] `MongoAtomAutoConfiguration` registered in
      `AutoConfiguration.imports`.
- [ ] `MongoAtomIT` uses Testcontainers MongoDB and exercises
      create/read/set/touch/delete, concurrent create collision,
      TTL expiry (Awaitility — MongoDB's TTL sweep runs every 60s
      but the application-level filter-on-read hides expired docs
      immediately).
- [ ] Apache 2.0 license headers on every new file.
- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`

## Implementation notes

- MongoDB's TTL sweeper runs approximately every 60 seconds, so
  expired documents can linger for up to a minute before physical
  removal. The filter-on-read on `expiresAt > now()` is load-bearing
  for correctness. Do not skip it.
- The TTL index creation on constructor call is idempotent — MongoDB
  won't fail if the index already exists with the same spec. If the
  index exists with a *different* spec (different field, different
  expireAfterSeconds), Mongo throws. Document this in the class
  javadoc: operators must not create their own `expiresAt` index
  manually, or must use the same spec.
- The existing `substrate-mongodb` module already has a `MongoClient`
  bean wired by Journal or Mailbox auto-config. Reuse it.
- Use `MongoClient` from the sync driver, not Reactive Streams
  driver — substrate is virtual-thread-based, not reactive.
- For the concurrent-create IT: the unique index on `_id` is what
  makes create atomic. MongoDB guarantees at most one `insertOne`
  succeeds per key; the others get `DuplicateKeyException`. Run the
  test with 8+ virtual threads to verify.
- `Binary` wraps `byte[]` for BSON binary storage. Use BSON type 0
  (default). Don't use type 4 (UUID) or other specialized subtypes.
