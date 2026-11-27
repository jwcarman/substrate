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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresNotifierSpi implements NotifierSpi, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PostgresNotifierSpi.class);

  private static final Pattern VALID_CHANNEL = Pattern.compile("^[a-zA-Z_]\\w*$");

  private static final Duration STOP_GRACE = Duration.ofSeconds(1);

  /**
   * How long the listener blocks in a single {@link PGConnection#getNotifications(int)} call, which
   * bounds how long {@link #stop()} waits for it. It does not affect notification latency: the call
   * returns as soon as a notification arrives. A poll that times out is a local socket read
   * timeout, not a round trip to the server.
   */
  private static final int POLL_SLICE_MILLIS = 100;

  private final JdbcTemplate jdbcTemplate;
  private final DataSource dataSource;
  private final String channel;
  private final List<Consumer<byte[]>> handlers = new CopyOnWriteArrayList<>();

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean listening = new AtomicBoolean(false);
  private final AtomicReference<Thread> listenerThread = new AtomicReference<>();
  final AtomicReference<Connection> listenConnection = new AtomicReference<>();

  public PostgresNotifierSpi(JdbcTemplate jdbcTemplate, DataSource dataSource, String channel) {
    if (!VALID_CHANNEL.matcher(channel).matches()) {
      throw new IllegalArgumentException(
          "Invalid PostgreSQL channel name: '" + channel + "'. Must match [a-zA-Z_][a-zA-Z0-9_]*");
    }
    this.jdbcTemplate = jdbcTemplate;
    this.dataSource = dataSource;
    this.channel = channel;
  }

  @Override
  public void notify(byte[] payload) {
    String encoded = Base64.getEncoder().encodeToString(payload);
    jdbcTemplate.execute(
        (Connection conn) -> {
          try (PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, channel);
            ps.setString(2, encoded);
            ps.execute();
            return null;
          }
        });
  }

  @Override
  public NotifierSubscription subscribe(Consumer<byte[]> handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void start() {
    running.set(true);
    listenerThread.set(Thread.ofVirtual().name("substrate-pg-listener").start(this::listenLoop));
  }

  /**
   * Stops listening. The listener thread notices within one poll slice ({@value #POLL_SLICE_MILLIS}
   * ms) and releases its connection itself; it is only interrupted if it fails to exit within that
   * window plus {@link #STOP_GRACE}. Interrupting a virtual thread blocked in a socket read closes
   * the socket, so an unconditional interrupt would hand a dead connection back to the pool.
   */
  @Override
  public void stop() {
    running.set(false);
    Thread thread = listenerThread.getAndSet(null);
    if (thread != null) {
      awaitListenerExit(thread);
    }
  }

  private void awaitListenerExit(Thread thread) {
    try {
      if (!thread.join(Duration.ofMillis(POLL_SLICE_MILLIS).plus(STOP_GRACE))) {
        log.warn("PostgreSQL listener did not stop in time; interrupting it");
        thread.interrupt();
      }
    } catch (InterruptedException _) {
      thread.interrupt();
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  public boolean isListening() {
    return listening.get();
  }

  private void listenLoop() {
    while (running.get()) {
      Connection conn = null;
      try {
        conn = dataSource.getConnection();
        listenConnection.set(conn);
        conn.setAutoCommit(true);
        PGConnection pgConnection = conn.unwrap(PGConnection.class);

        // LISTEN does not support prepared statements in PostgreSQL.
        // The channel name is validated at construction time against a strict pattern.
        try (Statement stmt = conn.createStatement()) {
          stmt.execute("LISTEN " + channel);
        }

        log.info("Listening on PostgreSQL channel '{}'", channel);
        listening.set(true);

        pollNotifications(pgConnection);
      } catch (SQLException e) {
        if (running.get()) {
          log.warn("PostgreSQL LISTEN connection lost, reconnecting", e);
        }
      } finally {
        listening.set(false);
        listenConnection.set(null);
        releaseListenConnection(conn);
      }
      if (running.get()) {
        sleepBeforeReconnect();
      }
    }
  }

  private void pollNotifications(PGConnection pgConnection) throws SQLException {
    while (running.get()) {
      PGNotification[] notifications = pgConnection.getNotifications(POLL_SLICE_MILLIS);
      if (notifications != null) {
        for (PGNotification notification : notifications) {
          dispatchNotification(notification.getParameter());
        }
      }
    }
  }

  void dispatchNotification(String encoded) {
    byte[] payload;
    try {
      payload = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException _) {
      log.warn("Ignoring malformed Base64 notification payload: {}", encoded);
      return;
    }
    for (Consumer<byte[]> handler : handlers) {
      handler.accept(payload);
    }
  }

  /**
   * Returns the LISTEN connection to the pool in a state the next borrower can use. {@code UNLISTEN
   * *} runs through the pool's connection proxy, so on a healthy connection it clears the session's
   * subscriptions, and on a dead one the pool sees the resulting connection-failure SQLSTATE and
   * evicts it. The failure of {@link PGConnection#getNotifications(int)} itself never reaches the
   * pool, because it is called on the unwrapped driver connection.
   */
  private void releaseListenConnection(Connection conn) {
    if (conn == null) {
      return;
    }
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("UNLISTEN *");
    } catch (SQLException e) {
      log.debug("LISTEN connection is broken; aborting it", e);
      abortQuietly(conn);
    } finally {
      closeQuietly(conn);
    }
  }

  private static void abortQuietly(Connection conn) {
    try {
      conn.abort(Runnable::run);
    } catch (SQLException e) {
      log.debug("Error aborting LISTEN connection", e);
    }
  }

  private static void closeQuietly(Connection conn) {
    try {
      conn.close();
    } catch (SQLException e) {
      log.debug("Error closing LISTEN connection", e);
    }
  }

  private void sleepBeforeReconnect() {
    try {
      Thread.sleep(1000);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
