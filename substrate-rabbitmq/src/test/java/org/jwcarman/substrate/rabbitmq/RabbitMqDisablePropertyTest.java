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
package org.jwcarman.substrate.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.stream.Environment;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.rabbitmq.journal.RabbitMqJournalAutoConfiguration;
import org.jwcarman.substrate.rabbitmq.journal.RabbitMqJournalSpi;
import org.jwcarman.substrate.rabbitmq.notifier.RabbitMqNotifierAutoConfiguration;
import org.jwcarman.substrate.rabbitmq.notifier.RabbitMqNotifierSpi;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RabbitMqDisablePropertyTest {

  @Test
  void journalIsDisabledWhenPropertyIsFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RabbitMqAutoConfiguration.class, RabbitMqJournalAutoConfiguration.class))
        .withUserConfiguration(MockConfiguration.class)
        .withPropertyValues("substrate.rabbitmq.journal.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RabbitMqJournalSpi.class));
  }

  @Test
  void notifierIsDisabledWhenPropertyIsFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RabbitMqAutoConfiguration.class, RabbitMqNotifierAutoConfiguration.class))
        .withUserConfiguration(MockConfiguration.class)
        .withPropertyValues("substrate.rabbitmq.notifier.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RabbitMqNotifierSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockConfiguration {

    @Bean
    Environment environment() {
      return mock(Environment.class);
    }

    @Bean
    ConnectionFactory connectionFactory() throws Exception {
      ConnectionFactory cf = mock(ConnectionFactory.class);
      Connection conn = mock(Connection.class);
      Channel channel = mock(Channel.class);
      AMQP.Exchange.DeclareOk exchangeOk = mock(AMQP.Exchange.DeclareOk.class);
      AMQP.Queue.DeclareOk queueOk = mock(AMQP.Queue.DeclareOk.class);
      AMQP.Queue.BindOk bindOk = mock(AMQP.Queue.BindOk.class);

      when(cf.createConnection()).thenReturn(conn);
      when(conn.createChannel(anyBoolean())).thenReturn(channel);
      when(channel.exchangeDeclare(anyString(), anyString(), anyBoolean(), anyBoolean(), any()))
          .thenReturn(exchangeOk);
      when(channel.queueDeclare(anyString(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
          .thenReturn(queueOk);
      when(queueOk.getQueue()).thenReturn("test-queue");
      when(channel.queueBind(anyString(), anyString(), anyString(), any())).thenReturn(bindOk);

      return cf;
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
      return new RabbitTemplate(connectionFactory);
    }
  }
}
