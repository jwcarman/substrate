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
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.postgresql.PostgresTestContainer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the notifier against a single-connection Hikari pool, so the connection the notifier hands
 * back to the pool is exactly the one the next borrower receives.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PostgresNotifierSpiPooledConnectionIT {

  private static final String CHANNEL = "substrate_pool_notify";

  private HikariDataSource pool;
  private PostgresNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresTestContainer.INSTANCE.getJdbcUrl());
    config.setUsername(PostgresTestContainer.INSTANCE.getUsername());
    config.setPassword(PostgresTestContainer.INSTANCE.getPassword());
    config.setMaximumPoolSize(1);
    config.setConnectionTimeout(10_000);
    pool = new HikariDataSource(config);
    notifier = new PostgresNotifierSpi(new JdbcTemplate(pool), pool, CHANNEL);
    notifier.start();
    await().atMost(Duration.ofSeconds(5)).until(notifier::isListening);
  }

  @AfterEach
  void tearDown() {
    if (notifier.isRunning()) {
      notifier.stop();
    }
    pool.close();
  }

  @Nested
  class Stop {

    @Test
    void returns_within_one_short_poll_even_when_a_poll_has_just_begun()
        throws SQLException, InterruptedException {
      CountDownLatch delivered = new CountDownLatch(1);
      notifier.subscribe(_ -> delivered.countDown());

      // The handler runs on the listener thread, which starts a fresh poll as soon as it returns,
      // so stopping right after delivery makes stop() wait out nearly a whole poll.
      notifyFromOutsideThePool();
      assertThat(delivered.await(5, TimeUnit.SECONDS)).as("notification delivered").isTrue();

      long started = System.nanoTime();
      notifier.stop();
      Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

      assertThat(elapsed).isLessThan(Duration.ofMillis(175));
    }
  }

  @Nested
  class After_stop {

    @Test
    void the_pool_hands_out_a_usable_connection() throws SQLException {
      notifier.stop();

      assertThat(selectOne()).isEqualTo(1);
    }

    @Test
    void the_pooled_connection_is_no_longer_listening() throws SQLException {
      notifier.stop();

      assertThat(listeningChannelCount()).isZero();
    }
  }

  @Nested
  class After_the_listen_connection_is_lost {

    @Test
    void the_pool_does_not_hand_out_the_dead_connection() throws SQLException {
      terminateListenerBackend();
      await().atMost(Duration.ofSeconds(5)).until(() -> !notifier.isListening());

      assertThat(selectOne()).isEqualTo(1);
    }
  }

  private int selectOne() throws SQLException {
    try (Connection conn = pool.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int listeningChannelCount() throws SQLException {
    try (Connection conn = pool.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT count(*) FROM pg_listening_channels()")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /**
   * Sends a notification over a connection outside the pool, whose only connection the listener
   * holds. The payload is "hi" in Base64.
   */
  private void notifyFromOutsideThePool() throws SQLException {
    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement()) {
      stmt.execute("SELECT pg_notify('" + CHANNEL + "', 'aGk=')");
    }
  }

  /** Kills the listener's backend from a connection outside the pool. */
  private void terminateListenerBackend() throws SQLException {
    try (Connection admin = adminConnection();
        Statement stmt = admin.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                    + " WHERE query = 'LISTEN "
                    + CHANNEL
                    + "' AND pid <> pg_backend_pid()")) {
      assertThat(rs.next()).as("listener backend found").isTrue();
      assertThat(rs.getBoolean(1)).as("listener backend terminated").isTrue();
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        PostgresTestContainer.INSTANCE.getJdbcUrl(),
        PostgresTestContainer.INSTANCE.getUsername(),
        PostgresTestContainer.INSTANCE.getPassword());
  }
}
