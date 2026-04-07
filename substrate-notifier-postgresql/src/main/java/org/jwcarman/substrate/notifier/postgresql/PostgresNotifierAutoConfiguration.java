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
package org.jwcarman.substrate.notifier.postgresql;

import javax.sql.DataSource;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration(before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(PostgresNotifierProperties.class)
@PropertySource("classpath:substrate-notifier-postgresql-defaults.properties")
public class PostgresNotifierAutoConfiguration {

  @Bean
  public PostgresNotifier postgresNotifier(
      DataSource dataSource, PostgresNotifierProperties properties) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    return new PostgresNotifier(
        jdbcTemplate, dataSource, properties.channel(), (int) properties.pollTimeout().toMillis());
  }
}
