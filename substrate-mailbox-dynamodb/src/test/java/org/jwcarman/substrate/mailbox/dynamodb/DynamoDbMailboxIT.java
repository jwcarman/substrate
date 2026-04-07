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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

@Testcontainers
class DynamoDbMailboxIT {

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
          .withServices("dynamodb");

  private DynamoDbMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    DynamoDbClient client =
        DynamoDbClient.builder()
            .endpointOverride(localstack.getEndpoint())
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        localstack.getAccessKey(), localstack.getSecretKey())))
            .region(Region.of(localstack.getRegion()))
            .build();

    try {
      client.deleteTable(DeleteTableRequest.builder().tableName("substrate_mailbox").build());
    } catch (ResourceNotFoundException _) {
      // table doesn't exist yet
    }

    mailbox =
        new DynamoDbMailboxSpi(
            client, "substrate:mailbox:", "substrate_mailbox", Duration.ofMinutes(5));
    mailbox.createTable();
  }

  @Test
  void deliverThenGetReturnsValue() {
    String key = mailbox.mailboxKey("test-" + System.nanoTime());

    mailbox.deliver(key, "hello".getBytes(StandardCharsets.UTF_8));

    Optional<byte[]> result = mailbox.get(key);
    assertThat(result).isPresent();
    assertThat(new String(result.get(), StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void getReturnsEmptyWhenNotDelivered() {
    String key = mailbox.mailboxKey("absent-" + System.nanoTime());

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-" + System.nanoTime());

    mailbox.deliver(key, "to-delete".getBytes(StandardCharsets.UTF_8));
    mailbox.delete(key);

    Optional<byte[]> result = mailbox.get(key);
    assertThat(result).isEmpty();
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }
}
