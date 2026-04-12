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
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresNotifierSpiTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final DataSource dataSource = mock(DataSource.class);

  @Test
  void constructorRejectsInvalidChannelName() {
    assertThatThrownBy(
            () -> new PostgresNotifierSpi(jdbcTemplate, dataSource, "invalid channel!", 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid PostgreSQL channel name");
  }

  @Test
  void constructorAcceptsValidChannelName() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    assertThat(spi).isNotNull();
  }

  @Test
  void dispatchNotificationIgnoresMalformedBase64() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    var received = new CopyOnWriteArrayList<byte[]>();
    spi.subscribe(received::add);

    spi.dispatchNotification("not-valid-base64!!!");

    assertThat(received).isEmpty();
  }

  @Test
  void dispatchNotificationDispatchesToAllHandlers() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    var received1 = new CopyOnWriteArrayList<byte[]>();
    var received2 = new CopyOnWriteArrayList<byte[]>();
    spi.subscribe(received1::add);
    spi.subscribe(received2::add);

    byte[] original = "my-value".getBytes(UTF_8);
    String encoded = Base64.getEncoder().encodeToString(original);
    spi.dispatchNotification(encoded);

    assertThat(received1).hasSize(1);
    assertThat(received1.get(0)).isEqualTo(original);
    assertThat(received2).hasSize(1);
    assertThat(received2.get(0)).isEqualTo(original);
  }

  @Test
  void subscribeReturnsCancellerThatRemovesHandler() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    var received = new CopyOnWriteArrayList<byte[]>();
    var subscription = spi.subscribe(received::add);

    byte[] first = "value1".getBytes(UTF_8);
    spi.dispatchNotification(Base64.getEncoder().encodeToString(first));
    assertThat(received).hasSize(1);

    subscription.cancel();

    byte[] second = "value2".getBytes(UTF_8);
    spi.dispatchNotification(Base64.getEncoder().encodeToString(second));
    assertThat(received).hasSize(1);
  }

  @Test
  void stopWhenNotStartedDoesNotThrow() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    spi.stop();
    assertThat(spi.isRunning()).isFalse();
  }

  @Test
  void isListeningReturnsFalseBeforeStart() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    assertThat(spi.isListening()).isFalse();
  }

  @Test
  void closeListenConnectionHandlesNullConnection() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    spi.listenConnection.set(null);
    spi.stop();
    assertThat(spi.listenConnection.get()).isNull();
  }
}
