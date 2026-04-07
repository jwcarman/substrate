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
package org.jwcarman.substrate.mailbox.postgresql;

import javax.sql.DataSource;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@AutoConfiguration(before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(PostgresMailboxProperties.class)
@PropertySource("classpath:substrate-mailbox-postgresql-defaults.properties")
public class PostgresMailboxAutoConfiguration {

  @Bean
  public PostgresMailboxSpi postgresMailbox(
      DataSource dataSource, PostgresMailboxProperties properties) {
    if (properties.autoCreateSchema()) {
      ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
      populator.addScript(new ClassPathResource("db/substrate/postgresql/V1__create_mailbox.sql"));
      populator.execute(dataSource);
    }

    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    return new PostgresMailboxSpi(jdbcTemplate, properties.prefix());
  }
}
