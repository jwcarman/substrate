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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@ExtendWith(MockitoExtension.class)
class DynamoDbAtomSpiTest {

  @Mock private DynamoDbClient client;

  private DynamoDbAtomSpi atom;

  @BeforeEach
  void setUp() {
    atom = new DynamoDbAtomSpi(client, "substrate:atom:", "substrate_atoms");
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(atom.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void createCallsPutItemWithCondition() {
    atom.create("key1", "value".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(client).putItem(captor.capture());

    PutItemRequest request = captor.getValue();
    assertThat(request.tableName()).isEqualTo("substrate_atoms");
    assertThat(request.conditionExpression()).isEqualTo("attribute_not_exists(pk)");
    assertThat(request.item().get("pk").s()).isEqualTo("key1");
    assertThat(request.item().get("value").b().asByteArray())
        .isEqualTo("value".getBytes(StandardCharsets.UTF_8));
    assertThat(request.item().get("token").s()).isEqualTo("tok1");
    assertThat(request.item().get("ttl").n()).isNotNull();
  }

  @Test
  void createThrowsAtomAlreadyExistsOnConditionalCheckFailure() {
    when(client.putItem(any(PutItemRequest.class)))
        .thenThrow(ConditionalCheckFailedException.builder().build());

    byte[] value = "value".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofMinutes(5);
    assertThatThrownBy(() -> atom.create("key1", value, "tok1", ttl))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void readReturnsEmptyWhenItemMissing() {
    when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

    assertThat(atom.read("key1")).isEmpty();
  }

  @Test
  void readReturnsAtomWhenItemExistsAndNotExpired() {
    long futureEpoch = Instant.now().plusSeconds(300).getEpochSecond();
    when(client.getItem(any(GetItemRequest.class)))
        .thenReturn(
            GetItemResponse.builder()
                .item(
                    Map.of(
                        "pk", AttributeValue.builder().s("key1").build(),
                        "value",
                            AttributeValue.builder()
                                .b(SdkBytes.fromByteArray("hello".getBytes(StandardCharsets.UTF_8)))
                                .build(),
                        "token", AttributeValue.builder().s("tok1").build(),
                        "ttl", AttributeValue.builder().n(Long.toString(futureEpoch)).build()))
                .build());

    var result = atom.read("key1");

    assertThat(result).isPresent();
    assertThat(new String(result.get().value(), StandardCharsets.UTF_8)).isEqualTo("hello");
    assertThat(result.get().token()).isEqualTo("tok1");
  }

  @Test
  void readReturnsEmptyWhenItemExpired() {
    long pastEpoch = Instant.now().minusSeconds(60).getEpochSecond();
    when(client.getItem(any(GetItemRequest.class)))
        .thenReturn(
            GetItemResponse.builder()
                .item(
                    Map.of(
                        "pk", AttributeValue.builder().s("key1").build(),
                        "value",
                            AttributeValue.builder()
                                .b(SdkBytes.fromByteArray("hello".getBytes(StandardCharsets.UTF_8)))
                                .build(),
                        "token", AttributeValue.builder().s("tok1").build(),
                        "ttl", AttributeValue.builder().n(Long.toString(pastEpoch)).build()))
                .build());

    assertThat(atom.read("key1")).isEmpty();
  }

  @Test
  void readUsesConsistentRead() {
    when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

    atom.read("key1");

    ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
    verify(client).getItem(captor.capture());
    assertThat(captor.getValue().consistentRead()).isTrue();
  }

  @Test
  void setReturnsTrueOnSuccess() {
    boolean applied =
        atom.set("key1", "value".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5));

    assertThat(applied).isTrue();

    ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(client).putItem(captor.capture());
    assertThat(captor.getValue().conditionExpression())
        .isEqualTo("attribute_exists(pk) AND #t > :now");
  }

  @Test
  void setReturnsFalseOnConditionalCheckFailure() {
    when(client.putItem(any(PutItemRequest.class)))
        .thenThrow(ConditionalCheckFailedException.builder().build());

    boolean applied =
        atom.set("key1", "value".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5));

    assertThat(applied).isFalse();
  }

  @Test
  void touchReturnsTrueOnSuccess() {
    boolean applied = atom.touch("key1", Duration.ofMinutes(10));

    assertThat(applied).isTrue();

    ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
    verify(client).updateItem(captor.capture());
    assertThat(captor.getValue().conditionExpression())
        .isEqualTo("attribute_exists(pk) AND #t > :now");
    assertThat(captor.getValue().updateExpression()).isEqualTo("SET #t = :newTtl");
  }

  @Test
  void touchReturnsFalseOnConditionalCheckFailure() {
    when(client.updateItem(any(UpdateItemRequest.class)))
        .thenThrow(ConditionalCheckFailedException.builder().build());

    assertThat(atom.touch("key1", Duration.ofMinutes(5))).isFalse();
  }

  @Test
  void deleteCallsDeleteItem() {
    atom.delete("key1");

    ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
    verify(client).deleteItem(captor.capture());
    assertThat(captor.getValue().tableName()).isEqualTo("substrate_atoms");
    assertThat(captor.getValue().key().get("pk").s()).isEqualTo("key1");
  }

  @Test
  void sweepReturnsZero() {
    assertThat(atom.sweep(100)).isZero();
  }
}
