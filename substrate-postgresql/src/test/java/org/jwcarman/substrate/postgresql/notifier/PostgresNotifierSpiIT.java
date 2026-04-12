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
package org.jwcarman.substrate.postgresql.notifier;

import static java.nio.charset.StandardCharsets.UTF_8;
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
import org.jwcarman.substrate.postgresql.PostgresTestContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PostgresNotifierSpiIT {

  private DataSource dataSource;
  private PostgresNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    dataSource = createDataSource();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    notifier = new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 500);
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

  @Test
  void invalidChannelNameThrowsException() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    assertThatThrownBy(
            () -> new PostgresNotifierSpi(jdbcTemplate, dataSource, "invalid:channel", 500))
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
    List<byte[]> received = new CopyOnWriteArrayList<>();
    notifier.subscribe(received::add);

    // Send a notification before the connection drop to verify baseline
    byte[] before = "before".getBytes(UTF_8);
    notifier.notify(before);
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(1));

    // Close the listener's dedicated connection to simulate connection loss
    notifier.listenConnection.get().close();

    // Wait for the listener to detect the loss and reconnect
    await().atMost(Duration.ofSeconds(10)).until(() -> !notifier.isListening());
    await().atMost(Duration.ofSeconds(10)).until(notifier::isListening);

    // Send a notification after reconnection
    byte[] after = "after".getBytes(UTF_8);
    notifier.notify(after);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(received).hasSizeGreaterThanOrEqualTo(2));
  }

  private DataSource createDataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(PostgresTestContainer.INSTANCE.getJdbcUrl());
    ds.setUsername(PostgresTestContainer.INSTANCE.getUsername());
    ds.setPassword(PostgresTestContainer.INSTANCE.getPassword());
    return ds;
  }
}
