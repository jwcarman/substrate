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
package org.jwcarman.substrate.nats.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueStatus;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.atom.AtomSpi;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.nats.NatsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NatsAtomAutoConfigurationTest {

  @Test
  void createsNatsAtomSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(NatsAutoConfiguration.class, NatsAtomAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NatsAtomSpi.class);
              assertThat(context).hasSingleBean(AtomSpi.class);
            });
  }

  @Test
  void natsAtomSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                NatsAutoConfiguration.class,
                NatsAtomAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(AtomSpi.class);
              assertThat(context.getBean(AtomSpi.class)).isInstanceOf(NatsAtomSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryAtomSpi.class);
            });
  }

  @Test
  void doesNotCreateAtomSpiWhenDisabled() {
    new ApplicationContextRunner()
        .withPropertyValues("substrate.nats.atom.enabled=false")
        .withConfiguration(
            AutoConfigurations.of(NatsAutoConfiguration.class, NatsAtomAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(NatsAtomSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockNatsConfiguration {

    @Bean
    Connection connection() throws Exception {
      Connection conn = mock(Connection.class);
      KeyValueManagement kvm = mock(KeyValueManagement.class);
      when(conn.keyValueManagement()).thenReturn(kvm);
      when(kvm.getStatus(anyString())).thenReturn(mock(KeyValueStatus.class));
      return conn;
    }
  }
}
