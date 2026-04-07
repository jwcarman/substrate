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
package org.jwcarman.substrate.notifier.rabbitmq;

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
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Testcontainers
class RabbitMqNotifierIT {

  @Container
  static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4-management");

  private CachingConnectionFactory connectionFactory;
  private RabbitMqNotifier notifier;

  @BeforeEach
  void setUp() {
    connectionFactory = new CachingConnectionFactory();
    connectionFactory.setHost(rabbitMQContainer.getHost());
    connectionFactory.setPort(rabbitMQContainer.getAmqpPort());
    connectionFactory.setUsername(rabbitMQContainer.getAdminUsername());
    connectionFactory.setPassword(rabbitMQContainer.getAdminPassword());

    RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
    notifier = new RabbitMqNotifier(rabbitTemplate, connectionFactory, "substrate-notify");
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
    List<String> receivedKeys = new CopyOnWriteArrayList<>();
    List<String> receivedPayloads = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          receivedKeys.add(key);
          receivedPayloads.add(payload);
          latch.countDown();
        });
    notifier.start();

    notifier.notify("test:key", "test-payload");

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedKeys).containsExactly("test:key");
    assertThat(receivedPayloads).containsExactly("test-payload");
  }

  @Test
  void multipleHandlersAllReceiveNotifications() throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    List<String> handler1Received = new CopyOnWriteArrayList<>();
    List<String> handler2Received = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          handler1Received.add(payload);
          latch.countDown();
        });
    notifier.subscribe(
        (key, payload) -> {
          handler2Received.add(payload);
          latch.countDown();
        });
    notifier.start();

    notifier.notify("key", "value");

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(handler1Received).containsExactly("value");
    assertThat(handler2Received).containsExactly("value");
  }

  @Test
  void multipleNotificationsAreDelivered() throws Exception {
    CountDownLatch latch = new CountDownLatch(3);
    List<String> receivedPayloads = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          receivedPayloads.add(payload);
          latch.countDown();
        });
    notifier.start();

    notifier.notify("key:1", "payload-1");
    notifier.notify("key:2", "payload-2");
    notifier.notify("key:3", "payload-3");

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedPayloads).containsExactly("payload-1", "payload-2", "payload-3");
  }

  @Test
  void stopPreventsNewNotifications() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<String> receivedPayloads = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          receivedPayloads.add(payload);
          latch.countDown();
        });
    notifier.start();

    notifier.notify("key", "first");
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    notifier.stop();
    notifier.notify("key", "second");

    await()
        .during(500, MILLISECONDS)
        .atMost(2, SECONDS)
        .untilAsserted(() -> assertThat(receivedPayloads).containsExactly("first"));
  }

  @Test
  void fanoutExchangeDeliversToMultipleConsumers() throws Exception {
    RabbitTemplate rabbitTemplate2 = new RabbitTemplate(connectionFactory);
    RabbitMqNotifier notifier2 =
        new RabbitMqNotifier(rabbitTemplate2, connectionFactory, "substrate-notify");

    CountDownLatch latch = new CountDownLatch(2);
    List<String> notifier1Received = new CopyOnWriteArrayList<>();
    List<String> notifier2Received = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          notifier1Received.add(payload);
          latch.countDown();
        });
    notifier2.subscribe(
        (key, payload) -> {
          notifier2Received.add(payload);
          latch.countDown();
        });

    notifier.start();
    notifier2.start();

    try {
      notifier.notify("key", "value");

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(notifier1Received).containsExactly("value");
      assertThat(notifier2Received).containsExactly("value");
    } finally {
      notifier2.stop();
    }
  }
}
