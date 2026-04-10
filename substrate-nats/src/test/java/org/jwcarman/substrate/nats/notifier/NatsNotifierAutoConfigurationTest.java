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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.MessageHandler;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.nats.NatsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NatsNotifierAutoConfigurationTest {

  @Test
  void createsNatsNotifierSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(NatsAutoConfiguration.class, NatsNotifierAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NatsNotifierSpi.class);
              assertThat(context).hasSingleBean(NotifierSpi.class);
            });
  }

  @Test
  void natsNotifierSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                NatsAutoConfiguration.class,
                NatsNotifierAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NotifierSpi.class);
              assertThat(context.getBean(NotifierSpi.class)).isInstanceOf(NatsNotifierSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockNatsConfiguration {

    @Bean
    Connection connection() {
      Connection conn = mock(Connection.class);
      Dispatcher dispatcher = mock(Dispatcher.class);
      when(conn.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
      when(dispatcher.subscribe(any(String.class))).thenReturn(dispatcher);
      return conn;
    }
  }
}
