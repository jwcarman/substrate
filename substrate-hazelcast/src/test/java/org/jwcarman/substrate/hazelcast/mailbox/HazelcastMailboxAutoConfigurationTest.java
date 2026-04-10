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
package org.jwcarman.substrate.hazelcast.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.mailbox.MailboxSpi;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.hazelcast.HazelcastAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class HazelcastMailboxAutoConfigurationTest {

  @Test
  void createsHazelcastMailboxSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                HazelcastAutoConfiguration.class, HazelcastMailboxAutoConfiguration.class))
        .withUserConfiguration(MockHazelcastConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(HazelcastMailboxSpi.class);
              assertThat(context).hasSingleBean(MailboxSpi.class);
            });
  }

  @Test
  void hazelcastMailboxSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                HazelcastAutoConfiguration.class,
                HazelcastMailboxAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockHazelcastConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MailboxSpi.class);
              assertThat(context.getBean(MailboxSpi.class)).isInstanceOf(HazelcastMailboxSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryMailboxSpi.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockHazelcastConfiguration {

    @Bean
    HazelcastInstance hazelcastInstance() {
      HazelcastInstance hazelcast = mock(HazelcastInstance.class);
      IMap<String, String> map = mock();
      doReturn(map).when(hazelcast).getMap(anyString());
      return hazelcast;
    }

    @Bean
    NotifierSpi notifier() {
      return mock(NotifierSpi.class);
    }
  }
}
