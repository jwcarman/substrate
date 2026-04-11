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
package org.jwcarman.substrate.dynamodb.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

@ExtendWith(MockitoExtension.class)
class DynamoDbJournalSpiTest {

  @Mock private DynamoDbClient client;

  private DynamoDbJournalSpi journal;

  @BeforeEach
  void setUp() {
    journal = new DynamoDbJournalSpi(client, "substrate:journal:", "substrate_journal");
  }

  @Test
  void appendPutsItemWithCorrectFields() {
    journal.append(
        "substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(client).putItem(captor.capture());

    PutItemRequest request = captor.getValue();
    assertThat(request.tableName()).isEqualTo("substrate_journal");

    Map<String, AttributeValue> item = request.item();
    assertThat(item.get("key").s()).isEqualTo("substrate:journal:test");
    assertThat(item.get("entry_id").s())
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    assertThat(item.get("data").b().asByteArray())
        .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    assertThat(item.get("timestamp").s()).isNotEmpty();
    assertThat(item.get("ttl").n()).isNotEmpty();
  }

  @Test
  void appendReturnsUuidV7Id() {
    String id =
        journal.append(
            "substrate:journal:test",
            "hello".getBytes(StandardCharsets.UTF_8),
            Duration.ofHours(1));
    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }

  @Test
  void appendWithNullTtlOmitsTtlField() {
    DynamoDbJournalSpi noTtlJournal =
        new DynamoDbJournalSpi(client, "substrate:journal:", "substrate_journal");
    noTtlJournal.append(
        "substrate:journal:test", "data".getBytes(StandardCharsets.UTF_8), Duration.ZERO);

    ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(client).putItem(captor.capture());

    assertThat(captor.getValue().item()).doesNotContainKey("ttl");
  }

  @Test
  void completePutsCompletionMarker() {
    journal.complete("substrate:journal:test", Duration.ofHours(1));

    ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(client).putItem(captor.capture());

    Map<String, AttributeValue> item = captor.getValue().item();
    assertThat(item.get("key").s()).isEqualTo("substrate:journal:test");
    assertThat(item.get("entry_id").s()).isEqualTo("COMPLETED");
  }

  @Test
  void readAfterQueriesWithCorrectKeyCondition() {
    when(client.query(any(QueryRequest.class)))
        .thenReturn(QueryResponse.builder().items(List.of()).build());

    journal.readAfter("substrate:journal:test", "00000000-0000-0000-0000-000000000000");

    ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
    verify(client).query(captor.capture());

    QueryRequest request = captor.getValue();
    assertThat(request.tableName()).isEqualTo("substrate_journal");
    assertThat(request.scanIndexForward()).isTrue();
    assertThat(request.keyConditionExpression()).contains("#k = :k AND entry_id > :eid");
  }

  @Test
  void readLastQueriesInReverseOrder() {
    when(client.query(any(QueryRequest.class)))
        .thenReturn(QueryResponse.builder().items(List.of()).build());

    journal.readLast("substrate:journal:test", 5);

    ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
    verify(client).query(captor.capture());

    QueryRequest request = captor.getValue();
    assertThat(request.scanIndexForward()).isFalse();
    assertThat(request.limit()).isEqualTo(6);
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }
}
