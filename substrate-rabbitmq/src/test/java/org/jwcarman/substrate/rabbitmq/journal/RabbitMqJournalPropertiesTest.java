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
package org.jwcarman.substrate.rabbitmq.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.rabbitmq.RabbitMqProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;

@SpringBootTest(classes = RabbitMqJournalPropertiesTest.Config.class)
class RabbitMqJournalPropertiesTest {

  @Autowired private RabbitMqProperties properties;

  @Test
  void defaultsAreLoadedFromPropertiesFile() {
    assertThat(properties.journal().prefix()).isEqualTo("substrate:journal:");
    assertThat(properties.journal().maxAge()).isEqualTo(Duration.ofHours(24));
    assertThat(properties.journal().maxLengthBytes()).isEqualTo(1073741824L);
  }

  @EnableConfigurationProperties(RabbitMqProperties.class)
  @PropertySource("classpath:substrate-rabbitmq-defaults.properties")
  static class Config {}
}
