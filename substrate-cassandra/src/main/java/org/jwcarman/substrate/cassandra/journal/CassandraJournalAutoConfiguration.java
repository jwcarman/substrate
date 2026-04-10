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

import com.datastax.oss.driver.api.core.CqlSession;
import org.jwcarman.substrate.cassandra.CassandraAutoConfiguration;
import org.jwcarman.substrate.cassandra.CassandraProperties;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.journal.JournalSpi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
    after = CassandraAutoConfiguration.class,
    before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(CqlSession.class)
@ConditionalOnProperty(
    prefix = "substrate.cassandra.journal",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CassandraJournalAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(JournalSpi.class)
  public CassandraJournalSpi cassandraJournal(
      CqlSession cqlSession, CassandraProperties properties) {
    CassandraJournalSpi journal =
        new CassandraJournalSpi(
            cqlSession,
            properties.journal().prefix(),
            properties.journal().tableName(),
            properties.journal().defaultTtl());

    if (properties.journal().autoCreateSchema()) {
      journal.createSchema();
    }

    return journal;
  }
}
