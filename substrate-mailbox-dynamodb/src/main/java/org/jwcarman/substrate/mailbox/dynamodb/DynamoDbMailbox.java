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
package org.jwcarman.substrate.mailbox.dynamodb;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.spi.AbstractMailbox;
import org.jwcarman.substrate.spi.Notifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

public class DynamoDbMailbox extends AbstractMailbox {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_TTL = "ttl";

  private final DynamoDbClient client;
  private final Notifier notifier;
  private final String tableName;
  private final Duration defaultTtl;
  private final ConcurrentMap<String, CompletableFuture<String>> pending =
      new ConcurrentHashMap<>();

  public DynamoDbMailbox(
      DynamoDbClient client,
      Notifier notifier,
      String prefix,
      String tableName,
      Duration defaultTtl) {
    super(prefix);
    this.client = client;
    this.notifier = notifier;
    this.tableName = tableName;
    this.defaultTtl = defaultTtl;
    this.notifier.subscribe(this::onNotification);
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
  public void deliver(String key, String value) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put(FIELD_KEY, AttributeValue.builder().s(key).build());
    item.put(FIELD_VALUE, AttributeValue.builder().s(value).build());

    if (!defaultTtl.isZero()) {
      long ttlEpoch = Instant.now().plus(defaultTtl).getEpochSecond();
      item.put(FIELD_TTL, AttributeValue.builder().n(String.valueOf(ttlEpoch)).build());
    }

    client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    notifier.notify(key, value);
  }

  @Override
  public CompletableFuture<String> await(String key, Duration timeout) {
    String existing = getValueFromDynamo(key);
    if (existing != null) {
      return CompletableFuture.completedFuture(existing);
    }

    CompletableFuture<String> future = pending.computeIfAbsent(key, k -> new CompletableFuture<>());

    // Double-check in case deliver() was called between our get and computeIfAbsent
    String deliveredAfter = getValueFromDynamo(key);
    if (deliveredAfter != null) {
      future.complete(deliveredAfter);
      pending.remove(key);
    }

    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void delete(String key) {
    client.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(FIELD_KEY, AttributeValue.builder().s(key).build()))
            .build());
    CompletableFuture<String> future = pending.remove(key);
    if (future != null) {
      future.cancel(false);
    }
  }

  private String getValueFromDynamo(String key) {
    GetItemResponse response =
        client.getItem(
            GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(FIELD_KEY, AttributeValue.builder().s(key).build()))
                .build());

    if (response.hasItem() && response.item().containsKey(FIELD_VALUE)) {
      return response.item().get(FIELD_VALUE).s();
    }
    return null;
  }

  private void onNotification(String key, String payload) {
    CompletableFuture<String> future = pending.remove(key);
    if (future != null) {
      future.complete(payload);
    }
  }
}
