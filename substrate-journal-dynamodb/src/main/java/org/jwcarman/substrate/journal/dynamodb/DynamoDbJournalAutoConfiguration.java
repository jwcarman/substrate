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
package org.jwcarman.substrate.journal.dynamodb;

import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@AutoConfiguration(before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(DynamoDbClient.class)
@EnableConfigurationProperties(DynamoDbJournalProperties.class)
@PropertySource("classpath:substrate-journal-dynamodb-defaults.properties")
public class DynamoDbJournalAutoConfiguration {

  @Bean
  public DynamoDbJournalSpi dynamoDbJournal(
      DynamoDbClient dynamoDbClient, DynamoDbJournalProperties properties) {
    DynamoDbJournalSpi journal =
        new DynamoDbJournalSpi(
            dynamoDbClient, properties.prefix(), properties.tableName(), properties.ttl());

    if (properties.autoCreateTable()) {
      journal.createTable();
    }

    return journal;
  }
}
