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
package org.jwcarman.substrate.mailbox.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.memory.InMemoryMailboxSpi;
import org.jwcarman.substrate.spi.MailboxSpi;
import org.jwcarman.substrate.spi.Notifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresMailboxAutoConfigurationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void createsPostgresMailboxSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresMailboxAutoConfiguration.class, SubstrateAutoConfiguration.class))
        .withUserConfiguration(PostgresMailboxTestConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(PostgresMailboxSpi.class);
              assertThat(context).hasSingleBean(MailboxSpi.class);
            });
  }

  @Test
  void postgresMailboxSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresMailboxAutoConfiguration.class, SubstrateAutoConfiguration.class))
        .withUserConfiguration(PostgresMailboxTestConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MailboxSpi.class);
              assertThat(context.getBean(MailboxSpi.class)).isInstanceOf(PostgresMailboxSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryMailboxSpi.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class PostgresMailboxTestConfiguration {

    @Bean
    DataSource dataSource() {
      DriverManagerDataSource ds = new DriverManagerDataSource();
      ds.setUrl(POSTGRES.getJdbcUrl());
      ds.setUsername(POSTGRES.getUsername());
      ds.setPassword(POSTGRES.getPassword());
      return ds;
    }

    @Bean
    Notifier notifier() {
      return mock(Notifier.class);
    }
  }
}
