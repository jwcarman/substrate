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
package org.jwcarman.substrate.postgresql;

import javax.sql.DataSource;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.core.io.DefaultResourceLoader;

/** Builds substrate-postgresql's schema for tests that construct the SPIs directly. */
public final class PostgresTestSchema {

  public static final String CHANGELOG =
      "classpath:org/jwcarman/substrate/postgresql/db/changelog.xml";

  private PostgresTestSchema() {}

  /**
   * Runs the shipped changelog against the data source, as migratory would. Running it again, or
   * against tables that already exist, changes nothing.
   *
   * @param dataSource the database to build the schema in
   */
  public static void create(DataSource dataSource) {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog(CHANGELOG);
    liquibase.setResourceLoader(new DefaultResourceLoader());
    try {
      liquibase.afterPropertiesSet();
    } catch (LiquibaseException e) {
      throw new IllegalStateException("Could not create the substrate-postgresql schema", e);
    }
  }
}
