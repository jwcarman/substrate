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
package org.jwcarman.substrate.notifier.nats;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NatsNotifierTest {

  @Mock private Connection connection;
  @Mock private Dispatcher dispatcher;

  private NatsNotifier notifier;

  @BeforeEach
  void setUp() {
    notifier = new NatsNotifier(connection, "substrate:notify:");
  }

  @Test
  void notifyPublishesToCorrectSubjectWithDotsInsteadOfColons() {
    notifier.notify("my:key", "my-payload");

    ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(connection).publish(eq("substrate.notify.my.key"), dataCaptor.capture());
    assertThat(new String(dataCaptor.getValue())).isEqualTo("my-payload");
  }

  @Test
  void startCreatesDispatcherAndSubscribes() {
    when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
    when(dispatcher.subscribe("substrate.notify.>")).thenReturn(dispatcher);

    notifier.start();

    verify(connection).createDispatcher(any(MessageHandler.class));
    verify(dispatcher).subscribe("substrate.notify.>");
    assertThat(notifier.isRunning()).isTrue();
  }

  @Test
  void stopClosesDispatcherAndBecomesNotRunning() {
    when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
    when(dispatcher.subscribe("substrate.notify.>")).thenReturn(dispatcher);

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
    List<String> handler1 = new ArrayList<>();
    List<String> handler2 = new ArrayList<>();

    notifier.subscribe((key, payload) -> handler1.add(payload));
    notifier.subscribe((key, payload) -> handler2.add(payload));

    assertThat(handler1).isEmpty();
    assertThat(handler2).isEmpty();
  }
}
