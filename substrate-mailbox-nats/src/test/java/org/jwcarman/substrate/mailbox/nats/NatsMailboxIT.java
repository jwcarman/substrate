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

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NatsMailboxIT {

  @Container
  private static final GenericContainer<?> NATS =
      new GenericContainer<>("nats:latest").withCommand("--jetstream").withExposedPorts(4222);

  private static Connection connection;
  private NatsMailboxSpi mailbox;

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
    mailbox =
        new NatsMailboxSpi(
            connection,
            "substrate:mailbox:",
            "substrate-mailbox-" + System.nanoTime(),
            Duration.ofMinutes(5));
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
