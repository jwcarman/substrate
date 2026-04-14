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
package org.jwcarman.substrate.etcd.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.etcd.jetcd.Client;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.etcd.EtcdAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class EtcdAtomAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(EtcdAutoConfiguration.class, EtcdAtomAutoConfiguration.class))
          .withUserConfiguration(MockEtcdConfiguration.class);

  @Test
  void registersEtcdAtomSpiBean() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(EtcdAtomSpi.class));
  }

  @Test
  void disabledPropertyPreventsBean() {
    contextRunner
        .withPropertyValues("substrate.etcd.atom.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(EtcdAtomSpi.class));
  }

  @Test
  void customPrefixIsApplied() {
    contextRunner
        .withPropertyValues("substrate.etcd.atom.prefix=custom:prefix:")
        .run(
            context -> {
              assertThat(context).hasSingleBean(EtcdAtomSpi.class);
              EtcdAtomSpi atom = context.getBean(EtcdAtomSpi.class);
              assertThat(atom.atomKey("my-key")).isEqualTo("custom:prefix:my-key");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockEtcdConfiguration {

    @Bean
    Client etcdClient() {
      return mock(Client.class);
    }
  }
}
