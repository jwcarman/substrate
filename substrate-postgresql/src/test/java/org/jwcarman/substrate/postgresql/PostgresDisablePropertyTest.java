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
package org.jwcarman.substrate.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.postgresql.journal.PostgresJournalAutoConfiguration;
import org.jwcarman.substrate.postgresql.journal.PostgresJournalSpi;
import org.jwcarman.substrate.postgresql.mailbox.PostgresMailboxAutoConfiguration;
import org.jwcarman.substrate.postgresql.mailbox.PostgresMailboxSpi;
import org.jwcarman.substrate.postgresql.notifier.PostgresNotifierAutoConfiguration;
import org.jwcarman.substrate.postgresql.notifier.PostgresNotifierSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PostgresDisablePropertyTest {

  @Test
  void journalDisabledDoesNotCreateBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class, PostgresJournalAutoConfiguration.class))
        .withPropertyValues("substrate.postgresql.journal.enabled=false")
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(PostgresJournalSpi.class));
  }

  @Test
  void mailboxDisabledDoesNotCreateBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class, PostgresMailboxAutoConfiguration.class))
        .withPropertyValues("substrate.postgresql.mailbox.enabled=false")
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(PostgresMailboxSpi.class));
  }

  @Test
  void notifierDisabledDoesNotCreateBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PostgresAutoConfiguration.class, PostgresNotifierAutoConfiguration.class))
        .withPropertyValues("substrate.postgresql.notifier.enabled=false")
        .withUserConfiguration(MockDataSourceConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(PostgresNotifierSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockDataSourceConfiguration {

    @Bean
    DataSource dataSource() {
      return mock(DataSource.class);
    }
  }
}
