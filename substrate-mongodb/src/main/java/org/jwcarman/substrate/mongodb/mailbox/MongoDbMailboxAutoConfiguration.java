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
package org.jwcarman.substrate.mongodb.mailbox;

import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.mongodb.MongoDbAutoConfiguration;
import org.jwcarman.substrate.mongodb.MongoDbProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

@AutoConfiguration(
    after = MongoDbAutoConfiguration.class,
    before = SubstrateAutoConfiguration.class)
@ConditionalOnProperty(
    prefix = "substrate.mongodb.mailbox",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MongoDbMailboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public MongoDbMailboxSpi mongoDbMailbox(
      MongoTemplate mongoTemplate, MongoDbProperties properties) {
    MongoDbProperties.MailboxProperties mailbox = properties.mailbox();
    MongoDbMailboxSpi spi =
        new MongoDbMailboxSpi(mongoTemplate, mailbox.prefix(), mailbox.collectionName());

    spi.ensureIndexes();

    return spi;
  }
}
