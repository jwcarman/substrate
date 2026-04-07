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
package org.jwcarman.substrate.mailbox.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisMailboxIT {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private RedisClient client;
  private RedisMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    client =
        RedisClient.create(
            RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getFirstMappedPort())
                .build());

    RedisCommands<String, String> commands = client.connect(StringCodec.UTF8).sync();
    mailbox = new RedisMailboxSpi(commands, "substrate:mailbox:", Duration.ofMinutes(5));
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.shutdown();
    }
  }

  @Test
  void deliverThenGetReturnsValue() {
    String key = mailbox.mailboxKey("deliver-first");
    mailbox.deliver(key, "hello".getBytes(StandardCharsets.UTF_8));

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getReturnsEmptyWhenNotDelivered() {
    String key = mailbox.mailboxKey("empty-test");

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-test");
    mailbox.deliver(key, "to-delete".getBytes(StandardCharsets.UTF_8));
    mailbox.delete(key);

    assertThat(mailbox.get(key)).isEmpty();
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-mailbox")).isEqualTo("substrate:mailbox:my-mailbox");
  }
}
