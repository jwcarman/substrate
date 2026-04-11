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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
  void dispatchNotificationIgnoresMalformedPayload() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    var received = new CopyOnWriteArrayList<String>();
    spi.subscribe((key, value) -> received.add(key + "=" + value));

    spi.dispatchNotification("no-delimiter-here");

    assertThat(received).isEmpty();
  }

  @Test
  void dispatchNotificationDispatchesToAllHandlers() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    var received1 = new CopyOnWriteArrayList<String>();
    var received2 = new CopyOnWriteArrayList<String>();
    spi.subscribe((key, value) -> received1.add(key + "=" + value));
    spi.subscribe((key, value) -> received2.add(key + "=" + value));

    spi.dispatchNotification("my-key|my-value");

    assertThat(received1).containsExactly("my-key=my-value");
    assertThat(received2).containsExactly("my-key=my-value");
  }

  @Test
  void subscribeReturnsCancellerThatRemovesHandler() {
    PostgresNotifierSpi spi =
        new PostgresNotifierSpi(jdbcTemplate, dataSource, "substrate_notify", 100);
    var received = new CopyOnWriteArrayList<String>();
    var subscription = spi.subscribe((key, value) -> received.add(key));

    spi.dispatchNotification("key1|value1");
    assertThat(received).hasSize(1);

    subscription.cancel();
    spi.dispatchNotification("key2|value2");
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
