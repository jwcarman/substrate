# `DynamoDbAtomSpi` — Atom backend for DynamoDB

**Depends on: spec 018 (Atom primitive) must be completed first.**

## What to build

Add an `AtomSpi` implementation to the `substrate-dynamodb` module
using DynamoDB's conditional writes for atomic set-if-not-exists and
the native TTL attribute for expiry.

## DynamoDB primitives used

- **`PutItem` with `ConditionExpression: attribute_not_exists(pk)`** —
  atomic create. Throws `ConditionalCheckFailedException` on collision.
- **`PutItem` with `ConditionExpression: attribute_exists(pk) AND ttl > :now`** —
  conditional write for `set` that only succeeds on a live atom.
- **`UpdateItem` with `ConditionExpression: attribute_exists(pk) AND ttl > :now`** —
  for `touch`, updates only the TTL attribute.
- **`GetItem`** — consistent-read fetch; filter on `ttl > now()` in
  application code (DynamoDB's TTL sweep is eventually consistent, so
  you may see expired items between expiry and physical deletion).
- **`DeleteItem`** — unconditional delete, idempotent.

DynamoDB handles TTL natively via the configured TTL attribute.
`DynamoDbAtomSpi.sweep(int)` inherits the `return 0` no-op from
`AbstractAtomSpi`. **But** because DynamoDB's sweep is eventually
consistent (items may linger up to 48 hours post-expiry),
`read`/`set`/`touch` must explicitly check the TTL value on every
operation and treat a too-old row as absent — this is standard
practice for DynamoDB TTL.

## Table schema

Single table with partition key `pk` (the atom's backend-qualified
key):

| Attribute | Type | Role |
|---|---|---|
| `pk` | `S` (string) | partition key — the atom's backend key |
| `value` | `B` (binary) | opaque codec-encoded bytes |
| `token` | `S` (string) | staleness token |
| `ttl` | `N` (number) | unix epoch seconds at which the item expires — enable DynamoDB TTL on this attribute |

Table name is configurable; default `substrate_atoms`. The operator is
responsible for creating the table and enabling the TTL feature on
the `ttl` attribute via AWS console / CDK / Terraform — substrate
does not `CreateTable` at runtime. (Add a startup check that the
table exists and logs a clear error if not.)

## Files created

```
substrate-dynamodb/src/main/java/org/jwcarman/substrate/dynamodb/atom/
  DynamoDbAtomSpi.java
  DynamoDbAtomAutoConfiguration.java

substrate-dynamodb/src/test/java/org/jwcarman/substrate/dynamodb/atom/
  DynamoDbAtomSpiTest.java
  DynamoDbAtomIT.java
```

## Files modified

- `substrate-dynamodb/src/main/java/org/jwcarman/substrate/dynamodb/DynamoDbProperties.java` —
  add nested `AtomProperties(boolean enabled, String tableName)` with
  defaults `(true, "substrate_atoms")`.
- `AutoConfiguration.imports` — register `DynamoDbAtomAutoConfiguration`.
- `substrate-dynamodb-defaults.properties` — document new properties.

## `DynamoDbAtomSpi` sketch

```java
package org.jwcarman.substrate.dynamodb.atom;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.AtomRecord;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public class DynamoDbAtomSpi extends AbstractAtomSpi {

  private final DynamoDbClient client;
  private final String tableName;

  public DynamoDbAtomSpi(DynamoDbClient client, String tableName) {
    super("substrate:atom:");
    this.client = client;
    this.tableName = tableName;
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    long expiresAt = Instant.now().plus(ttl).getEpochSecond();
    try {
      client.putItem(PutItemRequest.builder()
          .tableName(tableName)
          .item(Map.of(
              "pk", AttributeValue.fromS(key),
              "value", AttributeValue.fromB(SdkBytes.fromByteArray(value)),
              "token", AttributeValue.fromS(token),
              "ttl", AttributeValue.fromN(Long.toString(expiresAt))))
          .conditionExpression("attribute_not_exists(pk)")
          .build());
    } catch (ConditionalCheckFailedException e) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<AtomRecord> read(String key) {
    Map<String, AttributeValue> item = client.getItem(GetItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("pk", AttributeValue.fromS(key)))
        .consistentRead(true)
        .build()).item();
    if (item == null || item.isEmpty()) return Optional.empty();
    // Explicit TTL check — DynamoDB's sweep is eventually consistent.
    long ttlValue = Long.parseLong(item.get("ttl").n());
    if (Instant.now().getEpochSecond() >= ttlValue) {
      return Optional.empty();
    }
    byte[] bytes = item.get("value").b().asByteArray();
    String token = item.get("token").s();
    return Optional.of(new AtomRecord(bytes, token));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    long expiresAt = Instant.now().plus(ttl).getEpochSecond();
    long now = Instant.now().getEpochSecond();
    try {
      client.putItem(PutItemRequest.builder()
          .tableName(tableName)
          .item(Map.of(
              "pk", AttributeValue.fromS(key),
              "value", AttributeValue.fromB(SdkBytes.fromByteArray(value)),
              "token", AttributeValue.fromS(token),
              "ttl", AttributeValue.fromN(Long.toString(expiresAt))))
          .conditionExpression("attribute_exists(pk) AND #t > :now")
          .expressionAttributeNames(Map.of("#t", "ttl"))
          .expressionAttributeValues(Map.of(":now", AttributeValue.fromN(Long.toString(now))))
          .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    long expiresAt = Instant.now().plus(ttl).getEpochSecond();
    long now = Instant.now().getEpochSecond();
    try {
      client.updateItem(UpdateItemRequest.builder()
          .tableName(tableName)
          .key(Map.of("pk", AttributeValue.fromS(key)))
          .updateExpression("SET #t = :new")
          .conditionExpression("attribute_exists(pk) AND #t > :now")
          .expressionAttributeNames(Map.of("#t", "ttl"))
          .expressionAttributeValues(Map.of(
              ":new", AttributeValue.fromN(Long.toString(expiresAt)),
              ":now", AttributeValue.fromN(Long.toString(now))))
          .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  @Override
  public void delete(String key) {
    client.deleteItem(DeleteItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("pk", AttributeValue.fromS(key)))
        .build());
  }
}
```

## Auto-configuration

```java
@AutoConfiguration
@ConditionalOnClass(DynamoDbClient.class)
@ConditionalOnProperty(prefix = "substrate.dynamodb.atom",
                       name = "enabled",
                       havingValue = "true",
                       matchIfMissing = true)
public class DynamoDbAtomAutoConfiguration {

  @Bean
  @ConditionalOnBean(DynamoDbClient.class)
  @ConditionalOnMissingBean(AtomSpi.class)
  public DynamoDbAtomSpi dynamoDbAtomSpi(DynamoDbClient client, DynamoDbProperties props) {
    return new DynamoDbAtomSpi(client, props.atom().tableName());
  }
}
```

## Acceptance criteria

- [ ] `DynamoDbAtomSpi` implements all `AtomSpi` methods.
- [ ] `create` throws `AtomAlreadyExistsException` on a conditional
      check failure (key exists).
- [ ] `set` and `touch` both include `attribute_exists(pk) AND ttl >
      :now` in their condition expression and return `false` on
      conditional check failure.
- [ ] `read` explicitly checks `ttl > now()` in application code to
      handle DynamoDB's eventually-consistent sweep — expired items
      read back as `Optional.empty()` regardless of whether DynamoDB
      has physically removed them yet.
- [ ] `delete` is idempotent.
- [ ] TTL is stored as Unix epoch seconds in the `ttl` attribute.
- [ ] `sweep(int)` inherits the no-op from `AbstractAtomSpi` (DynamoDB
      handles expiry natively, with the read-side TTL check as a
      safety net).
- [ ] `DynamoDbProperties.atom` nested record exists with `enabled`
      and `tableName` defaults.
- [ ] `DynamoDbAtomAutoConfiguration` registered in
      `AutoConfiguration.imports` with `@ConditionalOnProperty`
      gating.
- [ ] `DynamoDbAtomIT` uses Testcontainers LocalStack or DynamoDB
      Local to exercise create/read/set/touch/delete, concurrent
      create collision, and TTL expiry (mock the clock if needed
      because DynamoDB's sweep is slow — prefer the read-side TTL
      check as the test verification).
- [ ] Apache 2.0 license headers on every new file.
- [ ] Spotless passes: `./mvnw spotless:check`
- [ ] Full build passes: `./mvnw verify`

## Implementation notes

- DynamoDB TTL sweep is eventually consistent — items may still
  appear in reads up to **48 hours** after expiry. The read-side TTL
  check in `read` is load-bearing. Do not skip it.
- DynamoDB TTL attribute must be a **Number** representing Unix
  epoch seconds. Don't use milliseconds or ISO strings.
- Use `consistentRead(true)` on `read` to avoid stale replica data
  after a fresh write.
- `ConditionalCheckFailedException` is the common "optimistic
  concurrency control failure" signal in DynamoDB. Catch and map to
  the substrate exception types per method.
- The existing `substrate-dynamodb` module already has a
  `DynamoDbClient` bean wired up by the Journal and Mailbox
  autoconfigs. Reuse it. Do not create a new client for the atom
  module.
- LocalStack and DynamoDB Local both support conditional writes
  correctly, so the IT can use either. Prefer DynamoDB Local for
  lower overhead; use LocalStack if the existing ITs in the module
  already use it.
- Because TTL cleanup is eventually consistent, the IT should not
  rely on "wait for DynamoDB to remove the item" — instead verify
  that the read-side TTL check correctly filters expired items.
  Use Awaitility only for the write-visibility window, not for
  TTL-based cleanup.
