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
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class DynamoDbAtomSpi extends AbstractAtomSpi {

  private static final String FIELD_PK = "pk";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_TTL = "ttl";

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

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    long expiresAt = Instant.now().plus(ttl).getEpochSecond();
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
    long ttlValue = Long.parseLong(item.get(FIELD_TTL).n());
    if (Instant.now().getEpochSecond() >= ttlValue) {
      return Optional.empty();
    }

    byte[] bytes = item.get(FIELD_VALUE).b().asByteArray();
    String token = item.get(FIELD_TOKEN).s();
    return Optional.of(new RawAtom(bytes, token));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
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
  public boolean touch(String key, Duration ttl) {
    long expiresAt = Instant.now().plus(ttl).getEpochSecond();
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
    long ttlValue = Long.parseLong(response.item().get(FIELD_TTL).n());
    return Instant.now().getEpochSecond() < ttlValue;
  }
}
