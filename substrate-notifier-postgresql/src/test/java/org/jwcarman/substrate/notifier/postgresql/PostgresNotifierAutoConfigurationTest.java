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
package org.jwcarman.substrate.notifier.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PostgresNotifierAutoConfigurationTest {

  @Test
  void createsPostgresNotifierBean() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PostgresNotifierAutoConfiguration.class))
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(PostgresNotifier.class);
              assertThat(context).hasSingleBean(NotifierSpi.class);
            });
  }

  @Test
  void postgresNotifierSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresNotifierAutoConfiguration.class, SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NotifierSpi.class);
              assertThat(context.getBean(NotifierSpi.class)).isInstanceOf(PostgresNotifier.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockDataSourceConfiguration {

    @Bean
    DataSource dataSource() throws SQLException {
      DataSource ds = mock(DataSource.class);
      given(ds.getConnection()).willThrow(new SQLException("mock DataSource"));
      return ds;
    }
  }
}
