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

import com.rabbitmq.stream.Environment;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

@AutoConfiguration(before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(Environment.class)
@EnableConfigurationProperties(RabbitMqJournalProperties.class)
@PropertySource("classpath:substrate-journal-rabbitmq-defaults.properties")
public class RabbitMqJournalAutoConfiguration {

  @Bean
  public RabbitMqJournal rabbitMqJournal(
      Environment environment, RabbitMqJournalProperties properties) {
    return new RabbitMqJournal(
        environment, properties.prefix(), properties.maxAge(), properties.maxLengthBytes());
  }
}
