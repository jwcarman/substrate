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
package org.jwcarman.substrate.core.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

class SubstratePropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Configuration
  @EnableConfigurationProperties(SubstrateProperties.class)
  @PropertySource("classpath:substrate-defaults.properties")
  static class PropertiesConfiguration {}

  @Test
  void propertiesBeanIsCreated() {
    contextRunner.run(
        context -> {
          SubstrateProperties properties = context.getBean(SubstrateProperties.class);
          assertNotNull(properties);
        });
  }

  @Test
  void defaultAtomMaxTtlIs24Hours() {
    contextRunner.run(
        context -> {
          SubstrateProperties props = context.getBean(SubstrateProperties.class);
          assertEquals(Duration.ofHours(24), props.atom().maxTtl());
        });
  }

  @Test
  void defaultJournalMaxTtlIs7Days() {
    contextRunner.run(
        context -> {
          SubstrateProperties props = context.getBean(SubstrateProperties.class);
          assertEquals(Duration.ofDays(7), props.journal().maxTtl());
        });
  }

  @Test
  void defaultMailboxMaxTtlIs30Minutes() {
    contextRunner.run(
        context -> {
          SubstrateProperties props = context.getBean(SubstrateProperties.class);
          assertEquals(Duration.ofMinutes(30), props.mailbox().maxTtl());
        });
  }
}
