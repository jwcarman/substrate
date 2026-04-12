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
package org.jwcarman.substrate.rabbitmq.notifier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.rabbitmq.RabbitMqTestContainer;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitMqNotifierSpiIT {

  private CachingConnectionFactory connectionFactory;
  private RabbitMqNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    connectionFactory = new CachingConnectionFactory();
    connectionFactory.setHost(RabbitMqTestContainer.INSTANCE.getHost());
    connectionFactory.setPort(RabbitMqTestContainer.INSTANCE.getAmqpPort());
    connectionFactory.setUsername(RabbitMqTestContainer.INSTANCE.getAdminUsername());
    connectionFactory.setPassword(RabbitMqTestContainer.INSTANCE.getAdminPassword());

    RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
    notifier = new RabbitMqNotifierSpi(rabbitTemplate, connectionFactory, "substrate-notify");
  }

  @AfterEach
  void tearDown() {
    if (notifier.isRunning()) {
      notifier.stop();
    }
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void notifyAndSubscribeRoundTrip() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<byte[]> received = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        payload -> {
          received.add(payload);
          latch.countDown();
        });
    notifier.start();

    byte[] payload = "test-payload".getBytes(UTF_8);
    notifier.notify(payload);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(received).hasSize(1);
    assertThat(received.get(0)).isEqualTo(payload);
  }

  @Test
  void multipleHandlersAllReceiveNotifications() throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    List<byte[]> handler1Received = new CopyOnWriteArrayList<>();
    List<byte[]> handler2Received = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        payload -> {
          handler1Received.add(payload);
          latch.countDown();
        });
    notifier.subscribe(
        payload -> {
          handler2Received.add(payload);
          latch.countDown();
        });
    notifier.start();

    byte[] payload = "value".getBytes(UTF_8);
    notifier.notify(payload);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(handler1Received).containsExactly(payload);
    assertThat(handler2Received).containsExactly(payload);
  }

  @Test
  void multipleNotificationsAreDelivered() throws Exception {
    CountDownLatch latch = new CountDownLatch(3);
    List<byte[]> received = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        payload -> {
          received.add(payload);
          latch.countDown();
        });
    notifier.start();

    byte[] p1 = "payload-1".getBytes(UTF_8);
    byte[] p2 = "payload-2".getBytes(UTF_8);
    byte[] p3 = "payload-3".getBytes(UTF_8);
    notifier.notify(p1);
    notifier.notify(p2);
    notifier.notify(p3);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(received).containsExactly(p1, p2, p3);
  }

  @Test
  void stopPreventsNewNotifications() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<byte[]> received = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        payload -> {
          received.add(payload);
          latch.countDown();
        });
    notifier.start();

    byte[] first = "first".getBytes(UTF_8);
    notifier.notify(first);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    notifier.stop();
    notifier.notify("second".getBytes(UTF_8));

    await()
        .during(500, MILLISECONDS)
        .atMost(2, SECONDS)
        .untilAsserted(() -> assertThat(received).containsExactly(first));
  }

  @Test
  void fanoutExchangeDeliversToMultipleConsumers() throws Exception {
    RabbitTemplate rabbitTemplate2 = new RabbitTemplate(connectionFactory);
    RabbitMqNotifierSpi notifier2 =
        new RabbitMqNotifierSpi(rabbitTemplate2, connectionFactory, "substrate-notify");

    CountDownLatch latch = new CountDownLatch(2);
    List<byte[]> notifier1Received = new CopyOnWriteArrayList<>();
    List<byte[]> notifier2Received = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        payload -> {
          notifier1Received.add(payload);
          latch.countDown();
        });
    notifier2.subscribe(
        payload -> {
          notifier2Received.add(payload);
          latch.countDown();
        });

    notifier.start();
    notifier2.start();

    try {
      byte[] payload = "value".getBytes(UTF_8);
      notifier.notify(payload);

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(notifier1Received).containsExactly(payload);
      assertThat(notifier2Received).containsExactly(payload);
    } finally {
      notifier2.stop();
    }
  }
}
