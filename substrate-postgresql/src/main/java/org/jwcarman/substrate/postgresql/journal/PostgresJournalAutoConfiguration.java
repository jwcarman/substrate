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
package org.jwcarman.substrate.postgresql.journal;

import javax.sql.DataSource;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.journal.JournalSpi;
import org.jwcarman.substrate.postgresql.PostgresAutoConfiguration;
import org.jwcarman.substrate.postgresql.PostgresProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@AutoConfiguration(
    after = PostgresAutoConfiguration.class,
    before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(
    prefix = "substrate.postgresql.journal",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PostgresJournalAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(JournalSpi.class)
  public PostgresJournalSpi postgresJournal(DataSource dataSource, PostgresProperties properties) {
    if (properties.journal().autoCreateSchema()) {
      ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
      populator.addScript(new ClassPathResource("db/substrate/postgresql/V1__create_journal.sql"));
      populator.execute(dataSource);
    }

    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    return new PostgresJournalSpi(
        jdbcTemplate, properties.journal().prefix(), properties.journal().maxLen());
  }
}
