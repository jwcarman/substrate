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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.spi.Notifier;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@ExtendWith(MockitoExtension.class)
class DynamoDbMailboxTest {

  @Mock private DynamoDbClient client;
  @Mock private Notifier notifier;

  private DynamoDbMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox =
        new DynamoDbMailboxSpi(
            client, notifier, "substrate:mailbox:", "substrate_mailbox", Duration.ofMinutes(5));
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }

  @Test
  void deliverPutsItemAndNotifies() {
    mailbox.deliver("substrate:mailbox:test", "hello".getBytes(StandardCharsets.UTF_8));

    ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(client).putItem(captor.capture());

    Map<String, AttributeValue> item = captor.getValue().item();
    assertThat(item.get("key").s()).isEqualTo("substrate:mailbox:test");
    assertThat(item.get("value").b().asByteArray())
        .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    assertThat(item.get("ttl").n()).isNotEmpty();

    verify(notifier).notify("substrate:mailbox:test", "substrate:mailbox:test");
  }

  @Test
  void awaitReturnsExistingValue() {
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

    var future = mailbox.await("substrate:mailbox:test", Duration.ofSeconds(5));

    assertThat(future.join()).isEqualTo("existing-value".getBytes(StandardCharsets.UTF_8));
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
