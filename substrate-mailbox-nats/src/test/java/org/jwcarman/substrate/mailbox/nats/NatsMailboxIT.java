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
package org.jwcarman.substrate.mailbox.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.notifier.nats.NatsNotifier;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NatsMailboxIT {

  @Container
  private static final GenericContainer<?> NATS =
      new GenericContainer<>("nats:latest").withCommand("--jetstream").withExposedPorts(4222);

  private static Connection connection;
  private NatsNotifier notifier;
  private NatsMailbox mailbox;

  @BeforeAll
  static void connect() throws Exception {
    String url = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    connection = Nats.connect(new Options.Builder().server(url).build());
  }

  @AfterAll
  static void disconnect() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @BeforeEach
  void setUp() {
    notifier = new NatsNotifier(connection, "substrate:notify:");
    notifier.start();
    mailbox =
        new NatsMailbox(
            connection,
            notifier,
            "substrate:mailbox:",
            "substrate-mailbox-" + System.nanoTime(),
            Duration.ofMinutes(5));
  }

  @AfterEach
  void tearDown() {
    if (notifier != null && notifier.isRunning()) {
      notifier.stop();
    }
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

    // Deliver after a short delay
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

    // After deletion, a new await should not find the value
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
