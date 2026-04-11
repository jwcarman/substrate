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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;
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
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@ExtendWith(MockitoExtension.class)
class DynamoDbMailboxSpiTest {

  @Mock private DynamoDbClient client;

  private DynamoDbMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox = new DynamoDbMailboxSpi(client, "substrate:mailbox:", "substrate_mailbox");
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }

  @Test
  void deliverUsesConditionalUpdate() {
    mailbox.deliver("substrate:mailbox:test", "hello".getBytes(StandardCharsets.UTF_8));

    ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
    verify(client).updateItem(captor.capture());

    UpdateItemRequest request = captor.getValue();
    assertThat(request.tableName()).isEqualTo("substrate_mailbox");
    assertThat(request.conditionExpression())
        .isEqualTo("attribute_exists(#k) AND attribute_not_exists(#v)");
  }

  @Test
  void deliverThrowsExpiredWhenItemMissing() {
    when(client.updateItem(any(UpdateItemRequest.class)))
        .thenThrow(ConditionalCheckFailedException.builder().build());
    when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        MailboxExpiredException.class, () -> mailbox.deliver("substrate:mailbox:test", data));
  }

  @Test
  void deliverThrowsFullWhenAlreadyDelivered() {
    when(client.updateItem(any(UpdateItemRequest.class)))
        .thenThrow(ConditionalCheckFailedException.builder().build());
    when(client.getItem(any(GetItemRequest.class)))
        .thenReturn(
            GetItemResponse.builder()
                .item(Map.of("key", AttributeValue.builder().s("substrate:mailbox:test").build()))
                .build());

    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThrows(MailboxFullException.class, () -> mailbox.deliver("substrate:mailbox:test", data));
  }

  @Test
  void getReturnsValueWhenPresent() {
    when(client.getItem(any(GetItemRequest.class)))
        .thenReturn(
            GetItemResponse.builder()
                .item(
                    Map.of(
                        "key", AttributeValue.builder().s("substrate:mailbox:test").build(),
                        "value",
                            AttributeValue.builder()
                                .b(
                                    SdkBytes.fromByteArray(
                                        "existing-value".getBytes(StandardCharsets.UTF_8)))
                                .build()))
                .build());

    Optional<byte[]> result = mailbox.get("substrate:mailbox:test");

    assertThat(result).contains("existing-value".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getThrowsWhenAbsent() {
    when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

    assertThrows(MailboxExpiredException.class, () -> mailbox.get("substrate:mailbox:test"));
  }

  @Test
  void getReturnsEmptyWhenCreatedButNotDelivered() {
    when(client.getItem(any(GetItemRequest.class)))
        .thenReturn(
            GetItemResponse.builder()
                .item(Map.of("key", AttributeValue.builder().s("substrate:mailbox:test").build()))
                .build());

    Optional<byte[]> result = mailbox.get("substrate:mailbox:test");

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesItem() {
    mailbox.delete("substrate:mailbox:test");

    ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
    verify(client).deleteItem(captor.capture());

    assertThat(captor.getValue().tableName()).isEqualTo("substrate_mailbox");
    assertThat(captor.getValue().key().get("key").s()).isEqualTo("substrate:mailbox:test");
  }
}
