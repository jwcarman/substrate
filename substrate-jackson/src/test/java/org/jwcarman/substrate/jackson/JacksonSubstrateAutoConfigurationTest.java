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
package org.jwcarman.substrate.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;
import org.jwcarman.substrate.memory.InMemoryMailboxSpi;
import org.jwcarman.substrate.spi.JournalSpi;
import org.jwcarman.substrate.spi.MailboxSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class JacksonSubstrateAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonSubstrateAutoConfiguration.class));

  @Test
  void createsJournalFactoryWhenJournalAndObjectMapperExist() {
    contextRunner
        .withUserConfiguration(JournalAndObjectMapperConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(JacksonJournalFactory.class);
            });
  }

  @Test
  void createsMailboxFactoryWhenMailboxAndObjectMapperExist() {
    contextRunner
        .withUserConfiguration(MailboxAndObjectMapperConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(JacksonMailboxFactory.class);
            });
  }

  @Test
  void doesNotCreateJournalFactoryWhenJournalMissing() {
    contextRunner
        .withUserConfiguration(ObjectMapperOnlyConfiguration.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(JacksonJournalFactory.class);
            });
  }

  @Test
  void doesNotCreateMailboxFactoryWhenMailboxMissing() {
    contextRunner
        .withUserConfiguration(ObjectMapperOnlyConfiguration.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(JacksonMailboxFactory.class);
            });
  }

  @Test
  void doesNotCreateFactoriesWhenObjectMapperMissing() {
    contextRunner
        .withUserConfiguration(JournalAndMailboxOnlyConfiguration.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(JacksonJournalFactory.class);
              assertThat(context).doesNotHaveBean(JacksonMailboxFactory.class);
            });
  }

  @Test
  void createsBothFactoriesWhenAllBeansExist() {
    contextRunner
        .withUserConfiguration(AllBeansConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(JacksonJournalFactory.class);
              assertThat(context).hasSingleBean(JacksonMailboxFactory.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class JournalAndObjectMapperConfiguration {

    @Bean
    JournalSpi journal() {
      return new InMemoryJournalSpi();
    }

    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class MailboxAndObjectMapperConfiguration {

    @Bean
    MailboxSpi mailbox() {
      return new InMemoryMailboxSpi();
    }

    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ObjectMapperOnlyConfiguration {

    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class JournalAndMailboxOnlyConfiguration {

    @Bean
    JournalSpi journal() {
      return new InMemoryJournalSpi();
    }

    @Bean
    MailboxSpi mailbox() {
      return new InMemoryMailboxSpi();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AllBeansConfiguration {

    @Bean
    JournalSpi journal() {
      return new InMemoryJournalSpi();
    }

    @Bean
    MailboxSpi mailbox() {
      return new InMemoryMailboxSpi();
    }

    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().build();
    }
  }
}
