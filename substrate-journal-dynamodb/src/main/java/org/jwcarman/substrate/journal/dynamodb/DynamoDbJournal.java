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
package org.jwcarman.substrate.journal.dynamodb;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jwcarman.substrate.spi.AbstractJournal;
import org.jwcarman.substrate.spi.JournalEntry;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public class DynamoDbJournal extends AbstractJournal {

  private static final int BATCH_DELETE_SIZE = 25;
  private static final String FIELD_KEY = "key";
  private static final String FIELD_ENTRY_ID = "entry_id";
  private static final String FIELD_DATA = "data";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String FIELD_TTL = "ttl";
  private static final String COMPLETED_ENTRY_ID = "COMPLETED";

  private final DynamoDbClient client;
  private final String tableName;
  private final Duration ttl;

  public DynamoDbJournal(DynamoDbClient client, String prefix, String tableName, Duration ttl) {
    super(prefix);
    this.client = client;
    this.tableName = tableName;
    this.ttl = ttl;
  }

  public void createTable() {
    try {
      client.createTable(
          b ->
              b.tableName(tableName)
                  .keySchema(
                      ks -> ks.attributeName(FIELD_KEY).keyType(KeyType.HASH),
                      ks -> ks.attributeName(FIELD_ENTRY_ID).keyType(KeyType.RANGE))
                  .attributeDefinitions(
                      ad -> ad.attributeName(FIELD_KEY).attributeType(ScalarAttributeType.S),
                      ad -> ad.attributeName(FIELD_ENTRY_ID).attributeType(ScalarAttributeType.S))
                  .provisionedThroughput(pt -> pt.readCapacityUnits(5L).writeCapacityUnits(5L)));
    } catch (ResourceInUseException _) {
      // table already exists
    }
  }

  @Override
  public String append(String key, String data) {
    return append(key, data, ttl);
  }

  @Override
  public String append(String key, String data, Duration ttl) {
    String entryId = generateEntryId();

    Map<String, AttributeValue> item = new HashMap<>();
    item.put(FIELD_KEY, AttributeValue.builder().s(key).build());
    item.put(FIELD_ENTRY_ID, AttributeValue.builder().s(entryId).build());
    item.put(FIELD_DATA, AttributeValue.builder().s(data).build());
    item.put(FIELD_TIMESTAMP, AttributeValue.builder().s(Instant.now().toString()).build());

    if (ttl != null && !ttl.isZero()) {
      long ttlEpoch = Instant.now().plus(ttl).getEpochSecond();
      item.put(FIELD_TTL, AttributeValue.builder().n(String.valueOf(ttlEpoch)).build());
    }

    client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    return entryId;
  }

  @Override
  public Stream<JournalEntry> readAfter(String key, String afterId) {
    List<JournalEntry> entries = new ArrayList<>();
    Map<String, AttributeValue> exclusiveStartKey = null;

    do {
      QueryRequest.Builder queryBuilder =
          QueryRequest.builder()
              .tableName(tableName)
              .keyConditionExpression("#k = :k AND " + FIELD_ENTRY_ID + " > :eid")
              .expressionAttributeNames(Map.of("#k", FIELD_KEY))
              .expressionAttributeValues(
                  Map.of(
                      ":k", AttributeValue.builder().s(key).build(),
                      ":eid", AttributeValue.builder().s(afterId).build()))
              .scanIndexForward(true);

      if (exclusiveStartKey != null) {
        queryBuilder.exclusiveStartKey(exclusiveStartKey);
      }

      QueryResponse response = client.query(queryBuilder.build());
      for (Map<String, AttributeValue> item : response.items()) {
        if (!COMPLETED_ENTRY_ID.equals(item.get(FIELD_ENTRY_ID).s())) {
          entries.add(mapItem(item, key));
        }
      }
      exclusiveStartKey =
          response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
    } while (exclusiveStartKey != null);

    return entries.stream();
  }

  @Override
  public Stream<JournalEntry> readLast(String key, int count) {
    // Request count + 1 to account for possible COMPLETED marker
    QueryResponse response =
        client.query(
            QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#k = :k")
                .expressionAttributeNames(Map.of("#k", FIELD_KEY))
                .expressionAttributeValues(Map.of(":k", AttributeValue.builder().s(key).build()))
                .scanIndexForward(false)
                .limit(count + 1)
                .build());

    List<JournalEntry> entries = new ArrayList<>();
    for (Map<String, AttributeValue> item : response.items()) {
      if (!COMPLETED_ENTRY_ID.equals(item.get(FIELD_ENTRY_ID).s())) {
        entries.add(mapItem(item, key));
      }
    }

    // Take only last 'count' entries after filtering COMPLETED
    if (entries.size() > count) {
      entries = entries.subList(0, count);
    }
    Collections.reverse(entries);
    return entries.stream();
  }

  @Override
  public void complete(String key) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put(FIELD_KEY, AttributeValue.builder().s(key).build());
    item.put(FIELD_ENTRY_ID, AttributeValue.builder().s(COMPLETED_ENTRY_ID).build());
    item.put(FIELD_TIMESTAMP, AttributeValue.builder().s(Instant.now().toString()).build());

    client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
  }

  @Override
  public void delete(String key) {
    Map<String, AttributeValue> exclusiveStartKey = null;

    do {
      QueryRequest.Builder queryBuilder =
          QueryRequest.builder()
              .tableName(tableName)
              .keyConditionExpression("#k = :k")
              .expressionAttributeValues(Map.of(":k", AttributeValue.builder().s(key).build()))
              .projectionExpression("#k, " + FIELD_ENTRY_ID)
              .expressionAttributeNames(Map.of("#k", FIELD_KEY));

      if (exclusiveStartKey != null) {
        queryBuilder.exclusiveStartKey(exclusiveStartKey);
      }

      QueryResponse response = client.query(queryBuilder.build());
      List<Map<String, AttributeValue>> items = response.items();

      for (int i = 0; i < items.size(); i += BATCH_DELETE_SIZE) {
        List<WriteRequest> writeRequests =
            items.subList(i, Math.min(i + BATCH_DELETE_SIZE, items.size())).stream()
                .map(
                    item ->
                        WriteRequest.builder()
                            .deleteRequest(
                                b ->
                                    b.key(
                                        Map.of(
                                            FIELD_KEY, item.get(FIELD_KEY),
                                            FIELD_ENTRY_ID, item.get(FIELD_ENTRY_ID))))
                            .build())
                .toList();

        client.batchWriteItem(
            BatchWriteItemRequest.builder().requestItems(Map.of(tableName, writeRequests)).build());
      }

      exclusiveStartKey =
          response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
    } while (exclusiveStartKey != null);
  }

  private JournalEntry mapItem(Map<String, AttributeValue> item, String key) {
    String data = item.containsKey(FIELD_DATA) ? item.get(FIELD_DATA).s() : null;
    Instant timestamp = Instant.parse(item.get(FIELD_TIMESTAMP).s());
    return new JournalEntry(item.get(FIELD_ENTRY_ID).s(), key, data, timestamp);
  }
}
