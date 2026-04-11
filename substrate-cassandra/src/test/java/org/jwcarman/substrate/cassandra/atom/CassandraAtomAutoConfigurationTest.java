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
package org.jwcarman.substrate.cassandra.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.cassandra.CassandraAutoConfiguration;
import org.jwcarman.substrate.core.atom.AtomSpi;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CassandraAtomAutoConfigurationTest {

  @Test
  void createsCassandraAtomSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CassandraAutoConfiguration.class, CassandraAtomAutoConfiguration.class))
        .withUserConfiguration(MockCqlSessionConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(CassandraAtomSpi.class);
              assertThat(context).hasSingleBean(AtomSpi.class);
            });
  }

  @Test
  void doesNotCreateAtomSpiWhenDisabled() {
    new ApplicationContextRunner()
        .withPropertyValues("substrate.cassandra.atom.enabled=false")
        .withConfiguration(
            AutoConfigurations.of(
                CassandraAutoConfiguration.class, CassandraAtomAutoConfiguration.class))
        .withUserConfiguration(MockCqlSessionConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(CassandraAtomSpi.class));
  }

  @Test
  void cassandraAtomSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CassandraAutoConfiguration.class,
                CassandraAtomAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockCqlSessionConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(AtomSpi.class);
              assertThat(context.getBean(AtomSpi.class)).isInstanceOf(CassandraAtomSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryAtomSpi.class);
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
