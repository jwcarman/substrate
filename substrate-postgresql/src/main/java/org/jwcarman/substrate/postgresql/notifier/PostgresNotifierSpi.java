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

  private final JdbcTemplate jdbcTemplate;
  private final DataSource dataSource;
  private final String channel;
  private final int pollTimeoutMillis;
  private final List<Consumer<byte[]>> handlers = new CopyOnWriteArrayList<>();

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean listening = new AtomicBoolean(false);
  private final AtomicReference<Thread> listenerThread = new AtomicReference<>();
  final AtomicReference<Connection> listenConnection = new AtomicReference<>();

  public PostgresNotifierSpi(
      JdbcTemplate jdbcTemplate, DataSource dataSource, String channel, int pollTimeoutMillis) {
    if (!VALID_CHANNEL.matcher(channel).matches()) {
      throw new IllegalArgumentException(
          "Invalid PostgreSQL channel name: '" + channel + "'. Must match [a-zA-Z_][a-zA-Z0-9_]*");
    }
    this.jdbcTemplate = jdbcTemplate;
    this.dataSource = dataSource;
    this.channel = channel;
    this.pollTimeoutMillis = pollTimeoutMillis;
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

  @Override
  public void stop() {
    running.set(false);
    Thread thread = listenerThread.getAndSet(null);
    if (thread != null) {
      thread.interrupt();
    }
    closeListenConnection();
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
      try {
        Connection conn = dataSource.getConnection();
        listenConnection.set(conn);
        conn.setAutoCommit(true);
        PGConnection pgConnection = conn.unwrap(PGConnection.class);

        // LISTEN does not support prepared statements in PostgreSQL.
        // The channel name is validated at construction time against a strict pattern.
        try (Statement stmt = conn.createStatement()) {
          stmt.execute("LISTEN " + channel);
        }

        log.info("Listening on PostgreSQL channel '{}'", channel);

        pollNotifications(pgConnection);
      } catch (SQLException e) {
        listening.set(false);
        if (running.get()) {
          log.warn("PostgreSQL LISTEN connection lost, reconnecting", e);
          closeListenConnection();
          sleepBeforeReconnect();
        }
      }
    }
  }

  private void pollNotifications(PGConnection pgConnection) throws SQLException {
    boolean firstPoll = true;
    while (running.get()) {
      PGNotification[] notifications = pgConnection.getNotifications(pollTimeoutMillis);
      if (firstPoll) {
        listening.set(true);
        firstPoll = false;
      }
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
    } catch (IllegalArgumentException e) {
      log.warn("Ignoring malformed Base64 notification payload: {}", encoded);
      return;
    }
    for (Consumer<byte[]> handler : handlers) {
      handler.accept(payload);
    }
  }

  private void closeListenConnection() {
    Connection conn = listenConnection.getAndSet(null);
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException e) {
        log.debug("Error closing LISTEN connection", e);
      }
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
