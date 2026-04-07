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
package org.jwcarman.substrate.notifier.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisNotifierIT {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private RedisClient client;
  private RedisNotifier notifier;

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
  void publishAndSubscribeFullLifecycle() {
    List<String> received = new CopyOnWriteArrayList<>();
    notifier.subscribe((key, payload) -> received.add(key + "=" + payload));

    notifier.notify("test-key", "test-payload");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(received).containsExactly("test-key=test-payload"));
  }

  @Test
  void multipleNotificationsAreDelivered() {
    List<String> received = new CopyOnWriteArrayList<>();
    notifier.subscribe((key, payload) -> received.add(payload));

    notifier.notify("key-1", "payload-1");
    notifier.notify("key-2", "payload-2");
    notifier.notify("key-3", "payload-3");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(received).containsExactly("payload-1", "payload-2", "payload-3"));
  }

  @Test
  void multipleHandlersReceiveNotifications() {
    List<String> handler1 = new CopyOnWriteArrayList<>();
    List<String> handler2 = new CopyOnWriteArrayList<>();

    notifier.subscribe((key, payload) -> handler1.add(payload));
    notifier.subscribe((key, payload) -> handler2.add(payload));

    notifier.notify("key", "value");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(handler1).containsExactly("value");
              assertThat(handler2).containsExactly("value");
            });
  }
}
