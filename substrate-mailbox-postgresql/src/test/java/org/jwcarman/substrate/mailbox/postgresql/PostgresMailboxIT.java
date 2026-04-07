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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresMailboxIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  private DataSource dataSource;
  private PostgresMailboxSpi mailbox;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    dataSource = createDataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);

    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    populator.addScript(new ClassPathResource("db/substrate/postgresql/V1__create_mailbox.sql"));
    populator.execute(dataSource);

    jdbcTemplate.update("DELETE FROM substrate_mailbox");

    mailbox = new PostgresMailboxSpi(jdbcTemplate, "substrate:mailbox:");
  }

  @Test
  void deliverThenGetReturnsValue() {
    String key = mailbox.mailboxKey("deliver-first");
    mailbox.deliver(key, "hello".getBytes(StandardCharsets.UTF_8));

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).contains("hello".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getReturnsEmptyWhenNotDelivered() {
    String key = mailbox.mailboxKey("never-delivered");

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-test");
    mailbox.deliver(key, "to-delete".getBytes(StandardCharsets.UTF_8));
    mailbox.delete(key);

    Optional<byte[]> result = mailbox.get(key);
    assertThat(result).isEmpty();

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_mailbox WHERE key = ?", Integer.class, key);
    assertThat(count).isZero();
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-mailbox")).isEqualTo("substrate:mailbox:my-mailbox");
  }

  private DataSource createDataSource() {
    org.springframework.jdbc.datasource.DriverManagerDataSource ds =
        new org.springframework.jdbc.datasource.DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
