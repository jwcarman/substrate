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
package org.jwcarman.substrate.postgresql.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.postgresql.AbstractPostgresIT;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class PostgresMailboxIT extends AbstractPostgresIT {

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
    mailbox.create(key, Duration.ofMinutes(5));
    mailbox.deliver(key, "hello".getBytes(StandardCharsets.UTF_8));

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).contains("hello".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getThrowsWhenMailboxDoesNotExist() {
    String key = mailbox.mailboxKey("never-delivered");

    assertThrows(MailboxExpiredException.class, () -> mailbox.get(key));
  }

  @Test
  void getReturnsEmptyWhenCreatedButNotDelivered() {
    String key = mailbox.mailboxKey("created-not-delivered");
    mailbox.create(key, Duration.ofMinutes(5));

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-test");
    mailbox.create(key, Duration.ofMinutes(5));
    mailbox.deliver(key, "to-delete".getBytes(StandardCharsets.UTF_8));
    mailbox.delete(key);

    assertThrows(MailboxExpiredException.class, () -> mailbox.get(key));

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
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
