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
package org.jwcarman.substrate.mailbox.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.memory.InMemoryNotifier;
import org.jwcarman.substrate.spi.Notifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MongoDbMailboxIT {

  @Container static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

  private MongoDbMailboxSpi mailbox;
  private Notifier notifier;

  @BeforeEach
  void setUp() {
    MongoClient client = MongoClients.create(mongo.getConnectionString());
    MongoTemplate mongoTemplate = new MongoTemplate(client, "substrate_test");

    mongoTemplate.dropCollection("substrate_mailbox");

    notifier = new InMemoryNotifier();
    mailbox =
        new MongoDbMailboxSpi(
            mongoTemplate,
            notifier,
            "substrate:mailbox:",
            "substrate_mailbox",
            Duration.ofMinutes(5));
    mailbox.ensureIndexes();
  }

  @Test
  void deliverAndAwaitFullLifecycle() throws Exception {
    String key = mailbox.mailboxKey("test-" + System.nanoTime());

    mailbox.deliver(key, "hello".getBytes(StandardCharsets.UTF_8));

    CompletableFuture<byte[]> future = mailbox.await(key, Duration.ofSeconds(5));
    assertThat(new String(future.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8))
        .isEqualTo("hello");
  }

  @Test
  void awaitResolvesWhenDeliveredAfterWaiting() {
    String key = mailbox.mailboxKey("async-" + System.nanoTime());

    CompletableFuture<byte[]> future = mailbox.await(key, Duration.ofSeconds(10));

    CompletableFuture.runAsync(
        () -> {
          await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> true);
          mailbox.deliver(key, "delayed-value".getBytes(StandardCharsets.UTF_8));
        });

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(future.join())
                    .isEqualTo("delayed-value".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-" + System.nanoTime());

    mailbox.deliver(key, "to-delete".getBytes(StandardCharsets.UTF_8));
    mailbox.delete(key);

    CompletableFuture<byte[]> future = mailbox.await(key, Duration.ofMillis(500));
    assertThat(future)
        .failsWithin(Duration.ofSeconds(2))
        .withThrowableOfType(java.util.concurrent.ExecutionException.class);
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }
}
