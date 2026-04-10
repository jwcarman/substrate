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
package org.jwcarman.substrate.dynamodb.mailbox;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.substrate.core.mailbox.AbstractMailboxSpi;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;
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

public class DynamoDbMailboxSpi extends AbstractMailboxSpi {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_TTL = "ttl";

  private final DynamoDbClient client;
  private final String tableName;

  public DynamoDbMailboxSpi(DynamoDbClient client, String prefix, String tableName) {
    super(prefix);
    this.client = client;
    this.tableName = tableName;
  }

  public void createTable() {
    try {
      client.createTable(
          b ->
              b.tableName(tableName)
                  .keySchema(ks -> ks.attributeName(FIELD_KEY).keyType(KeyType.HASH))
                  .attributeDefinitions(
                      ad -> ad.attributeName(FIELD_KEY).attributeType(ScalarAttributeType.S))
                  .provisionedThroughput(pt -> pt.readCapacityUnits(5L).writeCapacityUnits(5L)));
    } catch (ResourceInUseException _) {
      // table already exists
    }
  }

  @Override
  public void create(String key, Duration ttl) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put(FIELD_KEY, AttributeValue.builder().s(key).build());

    if (!ttl.isZero()) {
      long ttlEpoch = Instant.now().plus(ttl).getEpochSecond();
      item.put(FIELD_TTL, AttributeValue.builder().n(String.valueOf(ttlEpoch)).build());
    }

    client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
  }

  @Override
  public void deliver(String key, byte[] value) {
    try {
      client.updateItem(
          UpdateItemRequest.builder()
              .tableName(tableName)
              .key(Map.of(FIELD_KEY, AttributeValue.builder().s(key).build()))
              .updateExpression("SET #v = :val")
              .conditionExpression("attribute_exists(#k) AND attribute_not_exists(#v)")
              .expressionAttributeNames(Map.of("#k", FIELD_KEY, "#v", FIELD_VALUE))
              .expressionAttributeValues(
                  Map.of(":val", AttributeValue.builder().b(SdkBytes.fromByteArray(value)).build()))
              .build());
    } catch (ConditionalCheckFailedException _) {
      GetItemResponse existing =
          client.getItem(
              GetItemRequest.builder()
                  .tableName(tableName)
                  .key(Map.of(FIELD_KEY, AttributeValue.builder().s(key).build()))
                  .build());
      if (existing.hasItem() && !existing.item().isEmpty()) {
        throw new MailboxFullException(key);
      }
      throw new MailboxExpiredException(key);
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    GetItemResponse response =
        client.getItem(
            GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(FIELD_KEY, AttributeValue.builder().s(key).build()))
                .build());

    if (!response.hasItem() || response.item().isEmpty()) {
      throw new MailboxExpiredException(key);
    }

    if (response.item().containsKey(FIELD_VALUE)) {
      return Optional.of(response.item().get(FIELD_VALUE).b().asByteArray());
    }
    return Optional.empty();
  }

  @Override
  public void delete(String key) {
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(FIELD_KEY, AttributeValue.builder().s(key).build()))
            .build());
  }
}
