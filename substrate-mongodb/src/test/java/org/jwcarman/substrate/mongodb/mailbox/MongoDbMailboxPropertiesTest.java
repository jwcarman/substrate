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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.mongodb.MongoDbProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;

@SpringBootTest(classes = MongoDbMailboxPropertiesTest.Config.class)
class MongoDbMailboxPropertiesTest {

  @Autowired private MongoDbProperties properties;

  @Test
  void defaultsAreLoadedFromPropertiesFile() {
    MongoDbProperties.MailboxProperties mailbox = properties.mailbox();
    assertThat(mailbox.enabled()).isTrue();
    assertThat(mailbox.prefix()).isEqualTo("substrate:mailbox:");
    assertThat(mailbox.collectionName()).isEqualTo("substrate_mailbox");
    assertThat(mailbox.defaultTtl()).isEqualTo(Duration.ofMinutes(5));
  }

  @EnableConfigurationProperties(MongoDbProperties.class)
  @PropertySource("classpath:substrate-mongodb-defaults.properties")
  static class Config {}
}
