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
package org.jwcarman.substrate.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.topic.ITopic;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.hazelcast.journal.HazelcastJournalAutoConfiguration;
import org.jwcarman.substrate.hazelcast.journal.HazelcastJournalSpi;
import org.jwcarman.substrate.hazelcast.mailbox.HazelcastMailboxAutoConfiguration;
import org.jwcarman.substrate.hazelcast.mailbox.HazelcastMailboxSpi;
import org.jwcarman.substrate.hazelcast.notifier.HazelcastNotifierAutoConfiguration;
import org.jwcarman.substrate.hazelcast.notifier.HazelcastNotifierSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

class HazelcastDisablePropertyTest {

  @Test
  void journalIsDisabledWhenPropertySetToFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                HazelcastAutoConfiguration.class, HazelcastJournalAutoConfiguration.class))
        .withUserConfiguration(MockHazelcastConfiguration.class)
        .withPropertyValues("substrate.hazelcast.journal.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(HazelcastJournalSpi.class));
  }

  @Test
  void mailboxIsDisabledWhenPropertySetToFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                HazelcastAutoConfiguration.class, HazelcastMailboxAutoConfiguration.class))
        .withUserConfiguration(MockHazelcastConfiguration.class)
        .withPropertyValues("substrate.hazelcast.mailbox.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(HazelcastMailboxSpi.class));
  }

  @Test
  void notifierIsDisabledWhenPropertySetToFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                HazelcastAutoConfiguration.class, HazelcastNotifierAutoConfiguration.class))
        .withUserConfiguration(MockHazelcastConfiguration.class)
        .withPropertyValues("substrate.hazelcast.notifier.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(HazelcastNotifierSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockHazelcastConfiguration {

    @Bean
    HazelcastInstance hazelcastInstance() {
      HazelcastInstance hazelcast = mock(HazelcastInstance.class);
      when(hazelcast.getConfig()).thenReturn(new Config());
      IMap<String, String> map = mock();
      doReturn(map).when(hazelcast).getMap(anyString());
      ITopic<String> topic = mock();
      doReturn(topic).when(hazelcast).getTopic(anyString());
      return hazelcast;
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
