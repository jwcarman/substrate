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
package org.jwcarman.substrate.cassandra.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.cassandra.CassandraProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;

@SpringBootTest(classes = CassandraJournalPropertiesTest.Config.class)
class CassandraJournalPropertiesTest {

  @Autowired private CassandraProperties properties;

  @Test
  void defaultsAreLoadedFromPropertiesFile() {
    assertThat(properties.journal().prefix()).isEqualTo("substrate:journal:");
    assertThat(properties.journal().tableName()).isEqualTo("substrate_journal");
    assertThat(properties.journal().autoCreateSchema()).isTrue();
    assertThat(properties.journal().defaultTtl()).isEqualTo(Duration.ofHours(24));
  }

  @EnableConfigurationProperties(CassandraProperties.class)
  @PropertySource("classpath:substrate-cassandra-defaults.properties")
  static class Config {}
}
