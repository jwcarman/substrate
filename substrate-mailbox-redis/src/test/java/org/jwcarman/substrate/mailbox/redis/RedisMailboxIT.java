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
package org.jwcarman.substrate.mailbox.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.notifier.redis.RedisNotifier;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisMailboxIT {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private RedisClient client;
  private RedisNotifier notifier;
  private RedisMailbox mailbox;

  @BeforeEach
  void setUp() {
    client =
        RedisClient.create(
            RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getFirstMappedPort())
                .build());

    StatefulRedisPubSubConnection<String, String> pubSubConnection =
        client.connectPubSub(StringCodec.UTF8);
    var publishConnection = client.connect(StringCodec.UTF8);

    notifier = new RedisNotifier(pubSubConnection, publishConnection.sync(), "substrate:notify:");
    notifier.start();

    RedisCommands<String, String> commands = client.connect(StringCodec.UTF8).sync();
    mailbox = new RedisMailbox(commands, notifier, "substrate:mailbox:", Duration.ofMinutes(5));
  }

  @AfterEach
  void tearDown() {
    if (notifier != null && notifier.isRunning()) {
      notifier.stop();
    }
    if (client != null) {
      client.shutdown();
    }
  }

  @Test
  void deliverThenAwaitReturnsImmediately() {
    String key = mailbox.mailboxKey("deliver-first");
    mailbox.deliver(key, "hello");

    CompletableFuture<String> future = mailbox.await(key, Duration.ofSeconds(5));

    assertThat(future).isCompletedWithValue("hello");
  }

  @Test
  void awaitThenDeliverResolvesViaPubSub() {
    String key = mailbox.mailboxKey("await-first");
    CompletableFuture<String> future = mailbox.await(key, Duration.ofSeconds(10));

    mailbox.deliver(key, "delayed-value");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(future).isCompletedWithValue("delayed-value"));
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-test");
    mailbox.deliver(key, "to-delete");
    mailbox.delete(key);

    RedisCommands<String, String> commands = client.connect(StringCodec.UTF8).sync();
    assertThat(commands.get(key)).isNull();
  }

  @Test
  void deleteCancelsPendingFuture() {
    String key = mailbox.mailboxKey("cancel-test");
    CompletableFuture<String> future = mailbox.await(key, Duration.ofSeconds(30));

    mailbox.delete(key);

    assertThat(future).isCancelled();
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-mailbox")).isEqualTo("substrate:mailbox:my-mailbox");
  }
}
