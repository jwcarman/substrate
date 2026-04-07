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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.notifier.postgresql.PostgresNotifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresMailboxIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private DataSource dataSource;
  private PostgresNotifier notifier;
  private PostgresMailbox mailbox;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    dataSource = createDataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);

    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    populator.addScript(new ClassPathResource("db/substrate/postgresql/V1__create_mailbox.sql"));
    populator.execute(dataSource);

    jdbcTemplate.update("DELETE FROM substrate_mailbox");

    notifier = new PostgresNotifier(jdbcTemplate, dataSource, "substrate_notify", 500);
    notifier.start();

    mailbox = new PostgresMailbox(jdbcTemplate, notifier, "substrate:mailbox:");
  }

  @AfterEach
  void tearDown() {
    if (notifier != null && notifier.isRunning()) {
      notifier.stop();
    }
  }

  @Test
  void deliverThenAwaitReturnsImmediately() {
    String key = mailbox.mailboxKey("deliver-first");
    mailbox.deliver(key, "hello");

    CompletableFuture<String> future = mailbox.await(key, Duration.ofSeconds(5));

    assertThat(future).isCompletedWithValue("hello");
  }

  @Test
  void awaitThenDeliverResolvesViaNotification() {
    String key = mailbox.mailboxKey("await-first");
    CompletableFuture<String> future = mailbox.await(key, Duration.ofSeconds(10));

    mailbox.deliver(key, "delayed-value");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(future).isCompletedWithValue("delayed-value"));
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-test");
    mailbox.deliver(key, "to-delete");
    mailbox.delete(key);

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM substrate_mailbox WHERE key = ?", Integer.class, key);
    assertThat(count).isEqualTo(0);
  }

  @Test
  void deleteCancelsPendingFuture() {
    String key = mailbox.mailboxKey("cancel-test");
    CompletableFuture<String> future = mailbox.await(key, Duration.ofSeconds(30));

    mailbox.delete(key);

    assertThat(future).isCancelled();
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
