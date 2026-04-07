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
package org.jwcarman.substrate.journal.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.rabbitmq.stream.Environment;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.memory.InMemoryJournal;
import org.jwcarman.substrate.spi.Journal;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RabbitMqJournalAutoConfigurationTest {

  @Test
  void createsRabbitMqJournalBean() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RabbitMqJournalAutoConfiguration.class))
        .withUserConfiguration(MockRabbitMqConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RabbitMqJournal.class);
              assertThat(context).hasSingleBean(Journal.class);
            });
  }

  @Test
  void rabbitMqJournalSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RabbitMqJournalAutoConfiguration.class, SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockRabbitMqConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Journal.class);
              assertThat(context.getBean(Journal.class)).isInstanceOf(RabbitMqJournal.class);
              assertThat(context).doesNotHaveBean(InMemoryJournal.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockRabbitMqConfiguration {

    @Bean
    Environment environment() {
      return mock(Environment.class);
    }
  }
}
