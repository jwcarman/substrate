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
package org.jwcarman.substrate.postgresql.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.journal.JournalSpi;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.postgresql.PostgresAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PostgresJournalAutoConfigurationTest {

  @Test
  void createsPostgresJournalSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class, PostgresJournalAutoConfiguration.class))
        .withPropertyValues("substrate.postgresql.journal.auto-create-schema=false")
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(PostgresJournalSpi.class);
              assertThat(context).hasSingleBean(JournalSpi.class);
            });
  }

  @Test
  void doesNotCreateJournalSpiWhenDisabled() {
    new ApplicationContextRunner()
        .withPropertyValues("substrate.postgresql.journal.enabled=false")
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class, PostgresJournalAutoConfiguration.class))
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(PostgresJournalSpi.class));
  }

  @Test
  void postgresJournalSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class,
                PostgresJournalAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withPropertyValues("substrate.postgresql.journal.auto-create-schema=false")
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(JournalSpi.class);
              assertThat(context.getBean(JournalSpi.class)).isInstanceOf(PostgresJournalSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryJournalSpi.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockDataSourceConfiguration {

    @Bean
    DataSource dataSource() {
      return mock(DataSource.class);
    }
  }
}
