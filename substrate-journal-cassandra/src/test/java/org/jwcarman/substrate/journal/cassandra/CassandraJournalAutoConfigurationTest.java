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
package org.jwcarman.substrate.journal.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;
import org.jwcarman.substrate.spi.JournalSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CassandraJournalAutoConfigurationTest {

  @Test
  void createsCassandraJournalSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CassandraJournalAutoConfiguration.class))
        .withUserConfiguration(MockCqlSessionConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(CassandraJournalSpi.class);
              assertThat(context).hasSingleBean(JournalSpi.class);
            });
  }

  @Test
  void cassandraJournalSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CassandraJournalAutoConfiguration.class, SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockCqlSessionConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(JournalSpi.class);
              assertThat(context.getBean(JournalSpi.class)).isInstanceOf(CassandraJournalSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryJournalSpi.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockCqlSessionConfiguration {

    @Bean
    CqlSession cqlSession() {
      CqlSession session = mock(CqlSession.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(session.prepare(any(String.class))).thenReturn(ps);
      return session;
    }
  }
}
