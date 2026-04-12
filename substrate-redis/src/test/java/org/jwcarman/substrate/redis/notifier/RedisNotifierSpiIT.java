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
package org.jwcarman.substrate.redis.notifier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.redis.RedisTestContainer;

class RedisNotifierSpiIT {

  private static final byte[] CHANNEL = "substrate:notifications".getBytes(UTF_8);

  private RedisClient client;
  private RedisNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    client =
        RedisClient.create(
            RedisURI.builder()
                .withHost(RedisTestContainer.INSTANCE.getHost())
                .withPort(RedisTestContainer.INSTANCE.getFirstMappedPort())
                .build());

    StatefulRedisPubSubConnection<byte[], byte[]> pubSubConnection =
        client.connectPubSub(ByteArrayCodec.INSTANCE);
    var publishConnection = client.connect(ByteArrayCodec.INSTANCE);

    notifier = new RedisNotifierSpi(pubSubConnection, publishConnection.sync(), CHANNEL);
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
    List<byte[]> received = new CopyOnWriteArrayList<>();
    notifier.subscribe(received::add);

    byte[] payload = "test-payload".getBytes(UTF_8);
    notifier.notify(payload);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(received).hasSize(1);
              assertThat(received.get(0)).isEqualTo(payload);
            });
  }

  @Test
  void multipleNotificationsAreDelivered() {
    List<byte[]> received = new CopyOnWriteArrayList<>();
    notifier.subscribe(received::add);

    byte[] p1 = "payload-1".getBytes(UTF_8);
    byte[] p2 = "payload-2".getBytes(UTF_8);
    byte[] p3 = "payload-3".getBytes(UTF_8);
    notifier.notify(p1);
    notifier.notify(p2);
    notifier.notify(p3);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(received).containsExactly(p1, p2, p3));
  }

  @Test
  void multipleHandlersReceiveNotifications() {
    List<byte[]> handler1 = new CopyOnWriteArrayList<>();
    List<byte[]> handler2 = new CopyOnWriteArrayList<>();

    notifier.subscribe(handler1::add);
    notifier.subscribe(handler2::add);

    byte[] payload = "value".getBytes(UTF_8);
    notifier.notify(payload);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(handler1).containsExactly(payload);
              assertThat(handler2).containsExactly(payload);
            });
  }
}
