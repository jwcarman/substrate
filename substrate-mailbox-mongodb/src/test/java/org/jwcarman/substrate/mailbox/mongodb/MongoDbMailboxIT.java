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
package org.jwcarman.substrate.mailbox.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MongoDbMailboxIT {

  @Container static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

  private MongoDbMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    MongoClient client = MongoClients.create(mongo.getConnectionString());
    MongoTemplate mongoTemplate = new MongoTemplate(client, "substrate_test");

    mongoTemplate.dropCollection("substrate_mailbox");

    mailbox =
        new MongoDbMailboxSpi(
            mongoTemplate, "substrate:mailbox:", "substrate_mailbox", Duration.ofMinutes(5));
    mailbox.ensureIndexes();
  }

  @Test
  void deliverThenGetReturnsValue() {
    String key = mailbox.mailboxKey("test-" + System.nanoTime());

    mailbox.deliver(key, "hello".getBytes(StandardCharsets.UTF_8));

    Optional<byte[]> result = mailbox.get(key);
    assertThat(result).isPresent();
    assertThat(new String(result.get(), StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void getReturnsEmptyWhenNotDelivered() {
    String key = mailbox.mailboxKey("absent-" + System.nanoTime());

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-" + System.nanoTime());

    mailbox.deliver(key, "to-delete".getBytes(StandardCharsets.UTF_8));
    mailbox.delete(key);

    Optional<byte[]> result = mailbox.get(key);
    assertThat(result).isEmpty();
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }
}
