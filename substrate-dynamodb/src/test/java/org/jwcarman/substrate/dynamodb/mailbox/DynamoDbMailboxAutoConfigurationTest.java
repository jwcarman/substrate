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
package org.jwcarman.substrate.dynamodb.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.mailbox.MailboxSpi;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.dynamodb.DynamoDbAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class DynamoDbMailboxAutoConfigurationTest {

  @Test
  void createsDynamoDbMailboxSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DynamoDbAutoConfiguration.class, DynamoDbMailboxAutoConfiguration.class))
        .withUserConfiguration(MockDynamoDbConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(DynamoDbMailboxSpi.class);
              assertThat(context).hasSingleBean(MailboxSpi.class);
            });
  }

  @Test
  void dynamoDbMailboxSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DynamoDbAutoConfiguration.class,
                DynamoDbMailboxAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockDynamoDbConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MailboxSpi.class);
              assertThat(context.getBean(MailboxSpi.class)).isInstanceOf(DynamoDbMailboxSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryMailboxSpi.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockDynamoDbConfiguration {

    @Bean
    DynamoDbClient dynamoDbClient() {
      return mock(DynamoDbClient.class);
    }

    @Bean
    NotifierSpi notifier() {
      return mock(NotifierSpi.class);
    }
  }
}
