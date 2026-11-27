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

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.migratory.autoconfigure.MigratoryAutoConfiguration;
import org.jwcarman.migratory.autoconfigure.MigratoryLiquibase;
import org.jwcarman.substrate.postgresql.atom.PostgresAtomAutoConfiguration;
import org.jwcarman.substrate.postgresql.atom.PostgresAtomSpi;
import org.jwcarman.substrate.postgresql.journal.PostgresJournalAutoConfiguration;
import org.jwcarman.substrate.postgresql.journal.PostgresJournalSpi;
import org.jwcarman.substrate.postgresql.mailbox.PostgresMailboxAutoConfiguration;
import org.jwcarman.substrate.postgresql.mailbox.PostgresMailboxSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Runs substrate-postgresql's schema the way an application meets it: migratory, Liquibase and the
 * PostgreSQL auto-configurations in one context. Every test gets a database of its own, so no test
 * sees another's tables or Liquibase history.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PostgresSchemaMigrationIT {

  private static final List<String> CHANGESETS =
      List.of(
          "substrate-postgresql-create-atom",
          "substrate-postgresql-create-journal",
          "substrate-postgresql-create-mailbox");

  /** The scripts substrate ran at startup before 0.12.0, kept as fixtures. */
  private static final String V1_SCRIPTS = "db/substrate/postgresql/legacy/";

  @Nested
  class On_an_empty_database {

    @Test
    void creates_every_table_and_records_three_changesets() {
      String url = freshDatabaseUrl();

      runner(url).run(context -> assertThat(context).hasNotFailed());

      assertThat(substrateTables(url))
          .containsExactlyInAnyOrder(
              "substrate_atom",
              "substrate_journal",
              "substrate_journal_entries",
              "substrate_mailbox");
      assertThat(recordedChangesets(url)).isEqualTo(CHANGESETS);
    }

    @Test
    void a_second_start_runs_nothing() {
      String url = freshDatabaseUrl();

      runner(url).run(context -> assertThat(context).hasNotFailed());
      runner(url).run(context -> assertThat(context).hasNotFailed());

      assertThat(recordedChangesets(url)).isEqualTo(CHANGESETS);
    }
  }

  @Nested
  class On_a_database_from_the_v1_scripts {

    @Test
    void adopts_tables_created_by_the_v1_scripts() {
      String url = freshDatabaseUrl();
      applyV1Scripts(url);
      jdbc(url)
          .update(
              "INSERT INTO substrate_atom (key, value, token, expires_at)"
                  + " VALUES ('substrate:atom:kept', '\\x01', 't', now() + interval '1 hour')");

      runner(url).run(context -> assertThat(context).hasNotFailed());

      assertThat(recordedChangesets(url)).isEqualTo(CHANGESETS);
      assertThat(
              jdbc(url)
                  .queryForObject(
                      "SELECT count(*) FROM substrate_atom WHERE key = 'substrate:atom:kept'",
                      Integer.class))
          .isEqualTo(1);
    }
  }

  @Nested
  class Compared_with_the_v1_scripts {

    @Test
    void builds_the_same_columns_and_indexes() {
      String fromChangelog = freshDatabaseUrl();
      runner(fromChangelog).run(context -> assertThat(context).hasNotFailed());

      String fromScripts = freshDatabaseUrl();
      applyV1Scripts(fromScripts);

      assertThat(columns(fromChangelog)).isNotEmpty().isEqualTo(columns(fromScripts));
      assertThat(indexes(fromChangelog)).isNotEmpty().isEqualTo(indexes(fromScripts));
      assertThat(sequences(fromChangelog)).isNotEmpty().isEqualTo(sequences(fromScripts));
      // BIGSERIAL makes the column own its sequence, so dropping the table drops the sequence too
      assertThat(journalEntryIdSequence(fromChangelog))
          .isEqualTo(journalEntryIdSequence(fromScripts))
          .isEqualTo("public.substrate_journal_entries_id_seq");
    }
  }

  @Nested
  class With_migratory_disabled {

    @Test
    void creates_no_tables() {
      String url = freshDatabaseUrl();

      runner(url)
          .withPropertyValues("migratory.enabled=false")
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(substrateTables(url)).isEmpty();
    }
  }

  @Nested
  class Bean_ordering {

    @Test
    void spi_beans_wait_for_database_initialization() {
      runner(freshDatabaseUrl())
          .run(
              context -> {
                String migratory = context.getBeanNamesForType(MigratoryLiquibase.class)[0];
                for (Class<?> spi :
                    List.of(
                        PostgresAtomSpi.class,
                        PostgresJournalSpi.class,
                        PostgresMailboxSpi.class)) {
                  String name = context.getBeanNamesForType(spi)[0];
                  assertThat(context.getBeanFactory().getBeanDefinition(name).getDependsOn())
                      .as(name)
                      .contains(migratory);
                }
              });
    }
  }

  @Nested
  class Without_liquibase {

    @Test
    void fails_startup_naming_substrate_postgresql() {
      runner(freshDatabaseUrl())
          .withClassLoader(new FilteredClassLoader("liquibase."))
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .message()
                    .contains("substrate-postgresql")
                    .contains("spring-boot-starter-liquibase");
              });
    }

    @Test
    void starts_when_migratory_is_disabled() {
      runner(freshDatabaseUrl())
          .withClassLoader(new FilteredClassLoader("liquibase."))
          .withPropertyValues("migratory.enabled=false")
          .run(context -> assertThat(context).hasNotFailed());
    }
  }

  private static ApplicationContextRunner runner(String url) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                LiquibaseAutoConfiguration.class,
                MigratoryAutoConfiguration.class,
                PostgresAutoConfiguration.class,
                PostgresAtomAutoConfiguration.class,
                PostgresJournalAutoConfiguration.class,
                PostgresMailboxAutoConfiguration.class))
        .withPropertyValues(
            "spring.datasource.url=" + url,
            "spring.datasource.username=" + PostgresTestContainer.INSTANCE.getUsername(),
            "spring.datasource.password=" + PostgresTestContainer.INSTANCE.getPassword(),
            // the module has no application changelog of its own; migratory still runs
            "spring.liquibase.enabled=false",
            "substrate.postgresql.notifier.enabled=false");
  }

  /** Creates an empty database in the shared container and returns a JDBC URL to it. */
  private static String freshDatabaseUrl() {
    String name =
        "substrate_" + HexFormat.of().toHexDigits(RandomGenerator.getDefault().nextLong());
    try (Connection admin =
            DriverManager.getConnection(
                PostgresTestContainer.INSTANCE.getJdbcUrl(),
                PostgresTestContainer.INSTANCE.getUsername(),
                PostgresTestContainer.INSTANCE.getPassword());
        Statement stmt = admin.createStatement()) {
      stmt.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("Could not create database " + name, e);
    }
    return "jdbc:postgresql://"
        + PostgresTestContainer.INSTANCE.getHost()
        + ":"
        + PostgresTestContainer.INSTANCE.getFirstMappedPort()
        + "/"
        + name;
  }

  /** Builds the schema the way substrate 0.9.0–0.11.0 did, from the V1 scripts. */
  private static void applyV1Scripts(String url) {
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    populator.addScript(new ClassPathResource(V1_SCRIPTS + "V1__create_atom.sql"));
    populator.addScript(new ClassPathResource(V1_SCRIPTS + "V1__create_journal.sql"));
    populator.addScript(new ClassPathResource(V1_SCRIPTS + "V1__create_mailbox.sql"));
    populator.execute(dataSource(url));
  }

  private static List<String> columns(String url) {
    return jdbc(url)
        .queryForList(
            "SELECT concat_ws(' | ', table_name, column_name, data_type,"
                + " character_maximum_length, is_nullable, column_default, is_identity)"
                + " FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name LIKE 'substrate\\_%'"
                + " ORDER BY table_name, column_name",
            String.class);
  }

  private static List<String> indexes(String url) {
    return jdbc(url)
        .queryForList(
            "SELECT indexdef FROM pg_indexes"
                + " WHERE schemaname = 'public' AND tablename LIKE 'substrate\\_%'"
                + " ORDER BY indexname",
            String.class);
  }

  private static String journalEntryIdSequence(String url) {
    return jdbc(url)
        .queryForObject(
            "SELECT pg_get_serial_sequence('substrate_journal_entries', 'id')", String.class);
  }

  private static List<String> sequences(String url) {
    return jdbc(url)
        .queryForList(
            "SELECT concat_ws(' | ', sequence_name, data_type, start_value, minimum_value,"
                + " maximum_value, increment, cycle_option)"
                + " FROM information_schema.sequences"
                + " WHERE sequence_schema = 'public' AND sequence_name LIKE 'substrate\\_%'"
                + " ORDER BY sequence_name",
            String.class);
  }

  private static Set<String> substrateTables(String url) {
    return Set.copyOf(
        jdbc(url)
            .queryForList(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_name LIKE 'substrate\\_%'",
                String.class));
  }

  private static List<String> recordedChangesets(String url) {
    JdbcTemplate jdbc = jdbc(url);
    Boolean hasHistory =
        jdbc.queryForObject("SELECT to_regclass('databasechangelog') IS NOT NULL", Boolean.class);
    if (!Boolean.TRUE.equals(hasHistory)) {
      return List.of();
    }
    return jdbc.queryForList(
        "SELECT id FROM databasechangelog"
            + " WHERE filename LIKE '%org/jwcarman/substrate/postgresql/db/changes/%'"
            + " ORDER BY orderexecuted",
        String.class);
  }

  private static JdbcTemplate jdbc(String url) {
    return new JdbcTemplate(dataSource(url));
  }

  private static DataSource dataSource(String url) {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(url);
    ds.setUsername(PostgresTestContainer.INSTANCE.getUsername());
    ds.setPassword(PostgresTestContainer.INSTANCE.getPassword());
    return ds;
  }
}
