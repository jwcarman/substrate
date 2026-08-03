/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.substrate.dynamodb.atom;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.CasResult;
import org.jwcarman.substrate.core.atom.RawAtom;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class DynamoDbAtomSpi extends AbstractAtomSpi {

  private static final String FIELD_PK = "pk";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_TTL = "ttl";
  private static final long MIN_TTL_SECONDS = 1L;

  private final DynamoDbClient client;
  private final String tableName;

  public DynamoDbAtomSpi(DynamoDbClient client, String prefix, String tableName) {
    super(prefix);
    this.client = client;
    this.tableName = tableName;
  }

  public void createTable() {
    try {
      client.createTable(
          b ->
              b.tableName(tableName)
                  .keySchema(ks -> ks.attributeName(FIELD_PK).keyType(KeyType.HASH))
                  .attributeDefinitions(
                      ad -> ad.attributeName(FIELD_PK).attributeType(ScalarAttributeType.S))
                  .provisionedThroughput(pt -> pt.readCapacityUnits(5L).writeCapacityUnits(5L)));
    } catch (ResourceInUseException _) {
      // table already exists
    }
  }

  /**
   * Converts a requested TTL into the absolute epoch-second stored in the {@code ttl} attribute.
   *
   * <p>DynamoDB expiry is second-granular, so the TTL is floored at one second exactly as Redis
   * ({@code Math.max(1, ttl.toSeconds())}), Cassandra and etcd do. The current instant is rounded
   * <em>up</em> to the next whole second first, so a floored TTL always buys a full second of life
   * rather than landing on the current second and reading back as already expired.
   */
  private static long expiresAt(Duration ttl) {
    Instant now = Instant.now();
    long nowCeiling = now.getNano() == 0 ? now.getEpochSecond() : now.getEpochSecond() + 1;
    return nowCeiling + Math.max(MIN_TTL_SECONDS, ttl.toSeconds());
  }

  /**
   * Returns whether an item represents a live atom — one carrying a {@code ttl} attribute that has
   * not yet elapsed.
   *
   * <p>DynamoDB reaps expired items lazily, so a present item is not automatically a live one and
   * every read path has to re-check the attribute itself. A hand-written or legacy row with no
   * {@code ttl} attribute at all carries no proof of life and is treated exactly like an expired
   * one, rather than throwing.
   *
   * @param item the item returned by DynamoDB, which must contain the {@code ttl} attribute in its
   *     projection
   * @return {@code true} if the item is present and unexpired
   */
  private static boolean isLive(Map<String, AttributeValue> item) {
    AttributeValue ttl = item.get(FIELD_TTL);
    return ttl != null && Instant.now().getEpochSecond() < Long.parseLong(ttl.n());
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    long expiresAt = expiresAt(ttl);
    try {
      client.putItem(
          PutItemRequest.builder()
              .tableName(tableName)
              .item(
                  Map.of(
                      FIELD_PK, AttributeValue.builder().s(key).build(),
                      FIELD_VALUE,
                          AttributeValue.builder().b(SdkBytes.fromByteArray(value)).build(),
                      FIELD_TOKEN, AttributeValue.builder().s(token).build(),
                      FIELD_TTL, AttributeValue.builder().n(Long.toString(expiresAt)).build()))
              .conditionExpression("attribute_not_exists(pk)")
              .build());
    } catch (ConditionalCheckFailedException _) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    GetItemResponse response =
        client.getItem(
            GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(FIELD_PK, AttributeValue.builder().s(key).build()))
                .consistentRead(true)
                .build());

    if (!response.hasItem() || response.item().isEmpty()) {
      return Optional.empty();
    }

    Map<String, AttributeValue> item = response.item();
    if (!isLive(item)) {
      return Optional.empty();
    }

    byte[] bytes = item.get(FIELD_VALUE).b().asByteArray();
    String token = item.get(FIELD_TOKEN).s();
    return Optional.of(new RawAtom(bytes, token));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    long expiresAt = expiresAt(ttl);
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
                      FIELD_TOKEN, AttributeValue.builder().s(token).build(),
                      FIELD_TTL, AttributeValue.builder().n(Long.toString(expiresAt)).build()))
              .conditionExpression("attribute_exists(pk) AND #t > :now")
              .expressionAttributeNames(Map.of("#t", FIELD_TTL))
              .expressionAttributeValues(
                  Map.of(":now", AttributeValue.builder().n(Long.toString(now)).build()))
              .build());
      return true;
    } catch (ConditionalCheckFailedException _) {
      return false;
    }
  }

  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    long expiresAt = expiresAt(ttl);
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
      return isLive(old) ? CasResult.TOKEN_MISMATCH : CasResult.ABSENT;
    }
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    long expiresAt = expiresAt(ttl);
    long now = Instant.now().getEpochSecond();
    try {
      client.updateItem(
          UpdateItemRequest.builder()
              .tableName(tableName)
              .key(Map.of(FIELD_PK, AttributeValue.builder().s(key).build()))
              .updateExpression("SET #t = :newTtl")
              .conditionExpression("attribute_exists(pk) AND #t > :now")
              .expressionAttributeNames(Map.of("#t", FIELD_TTL))
              .expressionAttributeValues(
                  Map.of(
                      ":newTtl", AttributeValue.builder().n(Long.toString(expiresAt)).build(),
                      ":now", AttributeValue.builder().n(Long.toString(now)).build()))
              .build());
      return true;
    } catch (ConditionalCheckFailedException _) {
      return false;
    }
  }

  @Override
  public void delete(String key) {
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(FIELD_PK, AttributeValue.builder().s(key).build()))
            .build());
  }

  @Override
  public boolean exists(String key) {
    GetItemResponse response =
        client.getItem(
            GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(FIELD_PK, AttributeValue.builder().s(key).build()))
                .projectionExpression("#t")
                .expressionAttributeNames(Map.of("#t", FIELD_TTL))
                .consistentRead(true)
                .build());
    if (!response.hasItem() || response.item().isEmpty()) {
      return false;
    }
    return isLive(response.item());
  }
}
