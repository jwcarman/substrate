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
package org.jwcarman.substrate.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.dynamodb.journal.DynamoDbJournalAutoConfiguration;
import org.jwcarman.substrate.dynamodb.journal.DynamoDbJournalSpi;
import org.jwcarman.substrate.dynamodb.mailbox.DynamoDbMailboxAutoConfiguration;
import org.jwcarman.substrate.dynamodb.mailbox.DynamoDbMailboxSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class DynamoDbDisablePropertyTest {

  @Test
  void journalIsDisabledWhenPropertySetToFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DynamoDbAutoConfiguration.class, DynamoDbJournalAutoConfiguration.class))
        .withUserConfiguration(MockDynamoDbConfiguration.class)
        .withPropertyValues("substrate.dynamodb.journal.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DynamoDbJournalSpi.class));
  }

  @Test
  void mailboxIsDisabledWhenPropertySetToFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DynamoDbAutoConfiguration.class, DynamoDbMailboxAutoConfiguration.class))
        .withUserConfiguration(MockDynamoDbConfiguration.class)
        .withPropertyValues("substrate.dynamodb.mailbox.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DynamoDbMailboxSpi.class));
  }

  @Test
  void journalIsEnabledByDefault() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DynamoDbAutoConfiguration.class, DynamoDbJournalAutoConfiguration.class))
        .withUserConfiguration(MockDynamoDbConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(DynamoDbJournalSpi.class));
  }

  @Test
  void mailboxIsEnabledByDefault() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DynamoDbAutoConfiguration.class, DynamoDbMailboxAutoConfiguration.class))
        .withUserConfiguration(MockDynamoDbConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(DynamoDbMailboxSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockDynamoDbConfiguration {

    @Bean
    DynamoDbClient dynamoDbClient() {
      return mock(DynamoDbClient.class);
    }
  }
}
