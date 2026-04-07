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
package org.jwcarman.substrate.mailbox.mongodb;

import com.mongodb.client.MongoClient;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.spi.Notifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.mongodb.core.MongoTemplate;

@AutoConfiguration(before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(MongoClient.class)
@EnableConfigurationProperties(MongoDbMailboxProperties.class)
@PropertySource("classpath:substrate-mailbox-mongodb-defaults.properties")
public class MongoDbMailboxAutoConfiguration {

  @Bean
  public MongoDbMailboxSpi mongoDbMailbox(
      MongoTemplate mongoTemplate, Notifier notifier, MongoDbMailboxProperties properties) {
    MongoDbMailboxSpi mailbox =
        new MongoDbMailboxSpi(
            mongoTemplate,
            notifier,
            properties.prefix(),
            properties.collectionName(),
            properties.defaultTtl());

    mailbox.ensureIndexes();

    return mailbox;
  }
}
