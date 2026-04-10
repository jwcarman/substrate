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
package org.jwcarman.substrate.nats.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.nats.NatsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;

@SpringBootTest(classes = NatsMailboxPropertiesTest.Config.class)
class NatsMailboxPropertiesTest {

  @Autowired private NatsProperties properties;

  @Test
  void defaultsAreLoadedFromPropertiesFile() {
    assertThat(properties.mailbox().prefix()).isEqualTo("substrate:mailbox:");
    assertThat(properties.mailbox().bucketName()).isEqualTo("substrate-mailbox");
    assertThat(properties.mailbox().defaultTtl()).isEqualTo(Duration.ofMinutes(5));
  }

  @EnableConfigurationProperties(NatsProperties.class)
  @PropertySource("classpath:substrate-nats-defaults.properties")
  static class Config {}
}
