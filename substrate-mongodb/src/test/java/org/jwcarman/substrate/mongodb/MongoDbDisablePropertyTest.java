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
package org.jwcarman.substrate.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.mongodb.journal.MongoDbJournalAutoConfiguration;
import org.jwcarman.substrate.mongodb.journal.MongoDbJournalSpi;
import org.jwcarman.substrate.mongodb.mailbox.MongoDbMailboxAutoConfiguration;
import org.jwcarman.substrate.mongodb.mailbox.MongoDbMailboxSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

class MongoDbDisablePropertyTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  MongoDbAutoConfiguration.class,
                  MongoDbJournalAutoConfiguration.class,
                  MongoDbMailboxAutoConfiguration.class))
          .withUserConfiguration(MockMongoConfiguration.class);

  @Test
  void journalDisabledWhenPropertySetToFalse() {
    runner
        .withPropertyValues("substrate.mongodb.journal.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(MongoDbJournalSpi.class));
  }

  @Test
  void mailboxDisabledWhenPropertySetToFalse() {
    runner
        .withPropertyValues("substrate.mongodb.mailbox.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(MongoDbMailboxSpi.class));
  }

  @Test
  void journalEnabledByDefault() {
    runner.run(context -> assertThat(context).hasSingleBean(MongoDbJournalSpi.class));
  }

  @Test
  void mailboxEnabledByDefault() {
    runner.run(context -> assertThat(context).hasSingleBean(MongoDbMailboxSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockMongoConfiguration {

    @Bean
    MongoTemplate mongoTemplate() {
      MongoTemplate template = mock(MongoTemplate.class);
      when(template.indexOps(anyString())).thenReturn(mock(IndexOperations.class));
      return template;
    }
  }
}
