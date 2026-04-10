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
package org.jwcarman.substrate.sns.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.sns.SnsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;

@SpringBootTest(classes = SnsPropertiesTest.Config.class)
class SnsPropertiesTest {

  @Autowired private SnsProperties properties;

  @Test
  void defaultsAreLoadedFromPropertiesFile() {
    assertThat(properties.notifier().autoCreateTopic()).isFalse();
    assertThat(properties.notifier().sqsMessageRetention()).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.notifier().sqsWaitTimeSeconds()).isEqualTo(20);
    assertThat(properties.notifier().topicArn()).isNull();
  }

  @EnableConfigurationProperties(SnsProperties.class)
  @PropertySource("classpath:substrate-sns-defaults.properties")
  static class Config {}
}
