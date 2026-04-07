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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.memory.InMemoryNotifier;
import org.jwcarman.substrate.spi.Notifier;
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

  private DynamoDbMailbox mailbox;
  private Notifier notifier;

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

    notifier = new InMemoryNotifier();
    mailbox =
        new DynamoDbMailbox(
            client, notifier, "substrate:mailbox:", "substrate_mailbox", Duration.ofMinutes(5));
    mailbox.createTable();
  }

  @Test
  void deliverAndAwaitFullLifecycle() throws Exception {
    String key = mailbox.mailboxKey("test-" + System.nanoTime());

    mailbox.deliver(key, "hello");

    CompletableFuture<String> future = mailbox.await(key, Duration.ofSeconds(5));
    assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("hello");
  }

  @Test
  void awaitResolvesWhenDeliveredAfterWaiting() {
    String key = mailbox.mailboxKey("async-" + System.nanoTime());

    CompletableFuture<String> future = mailbox.await(key, Duration.ofSeconds(10));

    CompletableFuture.runAsync(
        () -> {
          try {
            Thread.sleep(200);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          mailbox.deliver(key, "delayed-value");
        });

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(future).isCompletedWithValue("delayed-value"));
  }

  @Test
  void deleteRemovesValue() throws Exception {
    String key = mailbox.mailboxKey("delete-" + System.nanoTime());

    mailbox.deliver(key, "to-delete");
    mailbox.delete(key);

    CompletableFuture<String> future = mailbox.await(key, Duration.ofMillis(500));
    assertThat(future)
        .failsWithin(Duration.ofSeconds(2))
        .withThrowableOfType(java.util.concurrent.ExecutionException.class);
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }
}
