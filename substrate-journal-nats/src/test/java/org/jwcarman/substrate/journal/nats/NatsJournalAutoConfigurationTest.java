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
package org.jwcarman.substrate.journal.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;
import org.jwcarman.substrate.spi.JournalSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NatsJournalAutoConfigurationTest {

  @Test
  void createsNatsJournalSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NatsJournalAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NatsJournalSpi.class);
              assertThat(context).hasSingleBean(JournalSpi.class);
            });
  }

  @Test
  void natsJournalSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                NatsJournalAutoConfiguration.class, SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(JournalSpi.class);
              assertThat(context.getBean(JournalSpi.class)).isInstanceOf(NatsJournalSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryJournalSpi.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockNatsConfiguration {

    @Bean
    Connection connection() throws Exception {
      Connection conn = mock(Connection.class);
      JetStream js = mock(JetStream.class);
      JetStreamManagement jsm = mock(JetStreamManagement.class);
      when(conn.jetStream()).thenReturn(js);
      when(conn.jetStreamManagement()).thenReturn(jsm);
      when(jsm.addStream(any(StreamConfiguration.class))).thenReturn(null);
      return conn;
    }
  }
}
