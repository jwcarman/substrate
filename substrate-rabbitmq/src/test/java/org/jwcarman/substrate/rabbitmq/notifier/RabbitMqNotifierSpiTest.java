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
package org.jwcarman.substrate.rabbitmq.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class RabbitMqNotifierSpiTest {

  @Mock private RabbitTemplate rabbitTemplate;
  @Mock private ConnectionFactory connectionFactory;

  private RabbitMqNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    notifier = new RabbitMqNotifierSpi(rabbitTemplate, connectionFactory, "substrate-notify");
  }

  @Test
  void notifySendsMessageToFanoutExchange() {
    notifier.notify("my:key", "my-payload");

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq("substrate-notify"), eq(""), messageCaptor.capture());
    assertThat(new String(messageCaptor.getValue().getBody())).isEqualTo("my:key|my-payload");
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
