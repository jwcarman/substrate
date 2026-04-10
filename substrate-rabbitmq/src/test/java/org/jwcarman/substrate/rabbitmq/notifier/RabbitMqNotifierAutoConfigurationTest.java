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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.rabbitmq.RabbitMqAutoConfiguration;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RabbitMqNotifierAutoConfigurationTest {

  @Test
  void createsRabbitMqNotifierSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RabbitMqAutoConfiguration.class, RabbitMqNotifierAutoConfiguration.class))
        .withUserConfiguration(MockRabbitMqConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RabbitMqNotifierSpi.class);
              assertThat(context).hasSingleBean(NotifierSpi.class);
            });
  }

  @Test
  void rabbitMqNotifierSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RabbitMqAutoConfiguration.class,
                RabbitMqNotifierAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockRabbitMqConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NotifierSpi.class);
              assertThat(context.getBean(NotifierSpi.class))
                  .isInstanceOf(RabbitMqNotifierSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockRabbitMqConfiguration {

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
