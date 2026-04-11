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
package org.jwcarman.substrate.dynamodb.journal;

import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.journal.JournalSpi;
import org.jwcarman.substrate.dynamodb.DynamoDbAutoConfiguration;
import org.jwcarman.substrate.dynamodb.DynamoDbProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@AutoConfiguration(
    after = DynamoDbAutoConfiguration.class,
    before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(DynamoDbClient.class)
@ConditionalOnProperty(
    prefix = "substrate.dynamodb.journal",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DynamoDbJournalAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(JournalSpi.class)
  public DynamoDbJournalSpi dynamoDbJournal(
      DynamoDbClient dynamoDbClient, DynamoDbProperties properties) {
    DynamoDbJournalSpi journal =
        new DynamoDbJournalSpi(
            dynamoDbClient, properties.journal().prefix(), properties.journal().tableName());

    if (properties.journal().autoCreateTable()) {
      journal.createTable();
    }

    return journal;
  }
}
