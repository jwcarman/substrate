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
package org.jwcarman.substrate.journal.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.JournalEntry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisJournalIT {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private RedisClient client;
  private RedisJournal journal;
  private RedisCommands<String, String> commands;

  @BeforeEach
  void setUp() {
    client =
        RedisClient.create(
            RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getFirstMappedPort())
                .build());
    StatefulRedisConnection<String, String> connection = client.connect(StringCodec.UTF8);
    commands = connection.sync();
    journal = new RedisJournal(commands, "substrate:journal:", 100_000, Duration.ofHours(1));
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.shutdown();
    }
  }

  @Test
  void appendAndReadAfterFullLifecycle() {
    String key = journal.journalKey("test-stream");
    String id1 = journal.append(key, "first");
    String id2 = journal.append(key, "second");
    String id3 = journal.append(key, "third");

    List<JournalEntry> entries = journal.readAfter(key, id1).toList();

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo(id2);
    assertThat(entries.get(0).data()).isEqualTo("second");
    assertThat(entries.get(0).key()).isEqualTo(key);
    assertThat(entries.get(0).timestamp()).isNotNull();
    assertThat(entries.get(1).id()).isEqualTo(id3);
    assertThat(entries.get(1).data()).isEqualTo("third");
  }

  @Test
  void readLastReturnsEntriesInChronologicalOrder() {
    String key = journal.journalKey("last-test");
    journal.append(key, "a");
    journal.append(key, "b");
    journal.append(key, "c");

    List<JournalEntry> entries = journal.readLast(key, 2).toList();

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).data()).isEqualTo("b");
    assertThat(entries.get(1).data()).isEqualTo("c");
  }

  @Test
  void readAfterReturnsEmptyForNonexistentStream() {
    List<JournalEntry> entries = journal.readAfter("nonexistent:key", "0-0").toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteRemovesStream() {
    String key = journal.journalKey("delete-test");
    journal.append(key, "data");
    journal.delete(key);

    List<JournalEntry> entries = journal.readAfter(key, "0-0").toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void completeAndDeleteRemovesCompletionFlag() {
    String key = journal.journalKey("complete-test");
    journal.append(key, "data");
    journal.complete(key);

    assertThat(commands.get(key + ":completed")).isEqualTo("true");

    journal.delete(key);

    assertThat(commands.get(key + ":completed")).isNull();
  }

  @Test
  void appendWithCustomTtlSetsExpiration() {
    String key = journal.journalKey("ttl-test");
    journal.append(key, "data", Duration.ofMinutes(10));

    Long ttl = commands.ttl(key);
    assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(600);
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }
}
