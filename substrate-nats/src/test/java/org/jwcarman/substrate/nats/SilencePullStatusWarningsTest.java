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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.support.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

class SilencePullStatusWarningsTest {

  private SilencePullStatusWarnings listener;
  private Logger natsLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    listener = new SilencePullStatusWarnings();
    natsLogger = (Logger) LoggerFactory.getLogger("io.nats.client.impl.ErrorListenerLoggerImpl");
    appender = new ListAppender<>();
    appender.start();
    natsLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    natsLogger.detachAppender(appender);
  }

  @Test
  void suppresses404NoMessages() {
    Connection conn = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
    JetStreamSubscription sub = Mockito.mock(JetStreamSubscription.class);
    Status status = new Status(404, "No Messages");

    listener.pullStatusWarning(conn, sub, status);

    assertThat(appender.list).isEmpty();
  }

  @Test
  void forwardsOtherPullStatusWarnings() {
    Connection conn = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
    JetStreamSubscription sub = Mockito.mock(JetStreamSubscription.class);
    Status status = new Status(409, "Consumer Deleted");

    listener.pullStatusWarning(conn, sub, status);

    assertThat(appender.list)
        .hasSize(1)
        .first()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("409");
            });
  }

  @Test
  void tolerantOfNullStatus() {
    Connection conn = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
    JetStreamSubscription sub = Mockito.mock(JetStreamSubscription.class);

    listener.pullStatusWarning(conn, sub, null);

    assertThat(appender.list).hasSize(1);
  }
}
