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
package org.jwcarman.substrate.nats.notifier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.MessageHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NatsNotifierSpiTest {

  private static final String SUBJECT = "substrate.notifications";

  @Mock private Connection connection;
  @Mock private Dispatcher dispatcher;

  private NatsNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    notifier = new NatsNotifierSpi(connection, SUBJECT);
  }

  @Test
  void notifyPublishesToCorrectSubject() {
    byte[] payload = "my-payload".getBytes(UTF_8);

    notifier.notify(payload);

    verify(connection).publish(eq(SUBJECT), eq(payload));
  }

  @Test
  void startCreatesDispatcherAndSubscribes() {
    when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
    when(dispatcher.subscribe(SUBJECT)).thenReturn(dispatcher);

    notifier.start();

    verify(connection).createDispatcher(any(MessageHandler.class));
    verify(dispatcher).subscribe(SUBJECT);
    assertThat(notifier.isRunning()).isTrue();
  }

  @Test
  void stopClosesDispatcherAndBecomesNotRunning() {
    when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
    when(dispatcher.subscribe(SUBJECT)).thenReturn(dispatcher);

    notifier.start();
    notifier.stop();

    verify(connection).closeDispatcher(dispatcher);
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void stopWhenNotRunningIsNoOp() {
    notifier.stop();
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void multipleHandlersAreRegistered() {
    List<byte[]> handler1 = new ArrayList<>();
    List<byte[]> handler2 = new ArrayList<>();

    notifier.subscribe(handler1::add);
    notifier.subscribe(handler2::add);

    assertThat(handler1).isEmpty();
    assertThat(handler2).isEmpty();
  }
}
