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
package org.jwcarman.substrate.nats;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.support.Status;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that {@link SilencePullStatusWarnings} drops the 404 "No Messages" pull status and
 * forwards every other callback to its {@code ErrorListenerLoggerImpl} superclass.
 *
 * <p>Records are captured with a {@link java.util.logging} handler rather than a logback appender
 * because {@code ErrorListenerLoggerImpl} logs through JUL directly. JUL records only reach logback
 * once something installs {@code SLF4JBridgeHandler}, which Spring Boot does when a context boots —
 * so a logback-based assertion here passes or fails purely on whether a Spring Boot test happened
 * to run earlier in the same JVM. Attaching the handler to the JUL logger itself makes this test
 * independent of that ordering.
 */
class SilencePullStatusWarningsTest {

  private static final String NATS_LOGGER = "io.nats.client.impl.ErrorListenerLoggerImpl";

  private SilencePullStatusWarnings listener;
  private Logger natsLogger;
  private Handler handler;
  private Level previousLevel;
  private List<LogRecord> records;

  @BeforeEach
  void setUp() {
    listener = new SilencePullStatusWarnings();
    records = new CopyOnWriteArrayList<>();
    natsLogger = Logger.getLogger(NATS_LOGGER);
    previousLevel = natsLogger.getLevel();
    // Pin the level: when logback's LevelChangePropagator is active it may have raised this
    // logger above WARNING, which would suppress the very records under assertion.
    natsLogger.setLevel(Level.ALL);
    handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {
            // nothing is buffered
          }

          @Override
          public void close() {
            // nothing to release
          }
        };
    natsLogger.addHandler(handler);
  }

  @AfterEach
  void tearDown() {
    natsLogger.removeHandler(handler);
    natsLogger.setLevel(previousLevel);
  }

  @Test
  void suppresses404NoMessages() {
    Connection conn = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
    JetStreamSubscription sub = Mockito.mock(JetStreamSubscription.class);
    Status status = new Status(404, "No Messages");

    listener.pullStatusWarning(conn, sub, status);

    assertThat(records).isEmpty();
  }

  @Test
  void forwardsOtherPullStatusWarnings() {
    Connection conn = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
    JetStreamSubscription sub = Mockito.mock(JetStreamSubscription.class);
    Status status = new Status(409, "Consumer Deleted");

    listener.pullStatusWarning(conn, sub, status);

    assertThat(records)
        .hasSize(1)
        .first()
        .satisfies(
            record -> {
              assertThat(record.getLevel()).isEqualTo(Level.WARNING);
              assertThat(record.getMessage()).contains("409");
            });
  }

  @Test
  void tolerantOfNullStatus() {
    Connection conn = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
    JetStreamSubscription sub = Mockito.mock(JetStreamSubscription.class);

    listener.pullStatusWarning(conn, sub, null);

    assertThat(records).hasSize(1);
  }
}
