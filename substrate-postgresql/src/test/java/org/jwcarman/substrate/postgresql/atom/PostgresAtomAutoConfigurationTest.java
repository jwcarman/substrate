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
package org.jwcarman.substrate.postgresql.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.atom.AtomSpi;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.postgresql.PostgresAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PostgresAtomAutoConfigurationTest {

  @Test
  void createsPostgresAtomSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class,
                PostgresAtomAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withPropertyValues("substrate.postgresql.atom.auto-create-schema=false")
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(PostgresAtomSpi.class);
              assertThat(context).hasSingleBean(AtomSpi.class);
            });
  }

  @Test
  void doesNotCreateAtomSpiWhenDisabled() {
    new ApplicationContextRunner()
        .withPropertyValues("substrate.postgresql.atom.enabled=false")
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class, PostgresAtomAutoConfiguration.class))
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(PostgresAtomSpi.class));
  }

  @Test
  void postgresAtomSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class,
                PostgresAtomAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withPropertyValues("substrate.postgresql.atom.auto-create-schema=false")
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(AtomSpi.class);
              assertThat(context.getBean(AtomSpi.class)).isInstanceOf(PostgresAtomSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryAtomSpi.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockDataSourceConfiguration {

    @Bean
    DataSource dataSource() {
      return mock(DataSource.class);
    }

    @Bean
    NotifierSpi notifier() {
      return mock(NotifierSpi.class);
    }
  }
}
