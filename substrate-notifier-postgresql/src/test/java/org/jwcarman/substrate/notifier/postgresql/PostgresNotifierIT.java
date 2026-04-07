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
package org.jwcarman.substrate.notifier.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresNotifierIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private DataSource dataSource;
  private PostgresNotifier notifier;

  @BeforeEach
  void setUp() {
    dataSource = createDataSource();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    notifier = new PostgresNotifier(jdbcTemplate, dataSource, "substrate_notify", 500);
    notifier.start();
    await().atMost(Duration.ofSeconds(5)).until(notifier::isListening);
  }

  @AfterEach
  void tearDown() {
    if (notifier != null && notifier.isRunning()) {
      notifier.stop();
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

  @Test
  void invalidChannelNameThrowsException() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    assertThatThrownBy(() -> new PostgresNotifier(jdbcTemplate, dataSource, "invalid:channel", 500))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid PostgreSQL channel name");
  }

  @Test
  void startAndStopAreIdempotent() {
    assertThat(notifier.isRunning()).isTrue();
    notifier.stop();
    assertThat(notifier.isRunning()).isFalse();
    notifier.stop();
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void reconnectsAfterConnectionLoss() throws Exception {
    List<String> received = new CopyOnWriteArrayList<>();
    notifier.subscribe((key, payload) -> received.add(payload));

    // Send a notification before the connection drop to verify baseline
    notifier.notify("key", "before");
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(received).contains("before"));

    // Close the listener's dedicated connection to simulate connection loss
    notifier.listenConnection.get().close();

    // Wait for the listener to detect the loss and reconnect
    await().atMost(Duration.ofSeconds(10)).until(() -> !notifier.isListening());
    await().atMost(Duration.ofSeconds(10)).until(notifier::isListening);

    // Send a notification after reconnection
    notifier.notify("key", "after");
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(received).contains("after"));
  }

  private DataSource createDataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
