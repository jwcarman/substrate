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
package org.jwcarman.substrate.journal.nats;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.RawJournalEntry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NatsJournalSpiIT {

  @Container
  private static final GenericContainer<?> NATS =
      new GenericContainer<>("nats:latest").withCommand("--jetstream").withExposedPorts(4222);

  private static Connection connection;
  private NatsJournalSpi journal;

  @BeforeAll
  static void connect() throws Exception {
    String url = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    connection = Nats.connect(new Options.Builder().server(url).build());
  }

  @AfterAll
  static void disconnect() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @BeforeEach
  void setUp() {
    journal =
        new NatsJournalSpi(
            connection, "substrate:journal:", "substrate-journal", Duration.ofHours(1), 100000);
  }

  @Test
  void appendReturnsSequenceId() {
    String key = journal.journalKey("append-test-" + System.nanoTime());
    String id = journal.append(key, "hello".getBytes(StandardCharsets.UTF_8));

    assertThat(id).matches("\\d+");
  }

  @Test
  void appendReturnsMonotonicallyIncreasingIds() {
    String key = journal.journalKey("mono-" + System.nanoTime());
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    String id2 = journal.append(key, "second".getBytes(StandardCharsets.UTF_8));

    assertThat(Long.parseLong(id2)).isGreaterThan(Long.parseLong(id1));
  }

  @Test
  void readAfterReturnsEntriesInOrder() {
    String key = journal.journalKey("read-after-" + System.nanoTime());
    String id1 = journal.append(key, "payload1".getBytes(StandardCharsets.UTF_8));
    String id2 = journal.append(key, "payload2".getBytes(StandardCharsets.UTF_8));
    String id3 = journal.append(key, "payload3".getBytes(StandardCharsets.UTF_8));

    List<RawJournalEntry> entries = journal.readAfter(key, id1).toList();

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo(id2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("payload2");
    assertThat(entries.get(1).id()).isEqualTo(id3);
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("payload3");
  }

  @Test
  void readAfterReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent-" + System.nanoTime());
    List<RawJournalEntry> entries = journal.readAfter(key, "0").toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsLastNInChronologicalOrder() {
    String key = journal.journalKey("read-last-" + System.nanoTime());
    journal.append(key, "first".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "second".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "third".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "fourth".getBytes(StandardCharsets.UTF_8));

    List<RawJournalEntry> entries = journal.readLast(key, 2).toList();

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("third");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("fourth");
  }

  @Test
  void readLastReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent-" + System.nanoTime());
    List<RawJournalEntry> entries = journal.readLast(key, 5).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsAllWhenCountExceedsSize() {
    String key = journal.journalKey("small-" + System.nanoTime());
    journal.append(key, "one".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "two".getBytes(StandardCharsets.UTF_8));

    List<RawJournalEntry> entries = journal.readLast(key, 100).toList();

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("one");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("two");
  }

  @Test
  void deletePurgesSubject() {
    String key = journal.journalKey("delete-" + System.nanoTime());
    journal.append(key, "hello".getBytes(StandardCharsets.UTF_8));
    journal.append(key, "world".getBytes(StandardCharsets.UTF_8));

    journal.delete(key);

    List<RawJournalEntry> entries = journal.readLast(key, 100).toList();
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteDoesNotAffectOtherJournals() {
    String key1 = journal.journalKey("a-" + System.nanoTime());
    String key2 = journal.journalKey("b-" + System.nanoTime());
    journal.append(key1, "a-event".getBytes(StandardCharsets.UTF_8));
    journal.append(key2, "b-event".getBytes(StandardCharsets.UTF_8));

    journal.delete(key1);

    assertThat(journal.readLast(key1, 100).toList()).isEmpty();
    assertThat(journal.readLast(key2, 100).toList()).hasSize(1);
  }

  @Test
  void timestampIsPreserved() {
    String key = journal.journalKey("time-" + System.nanoTime());
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8));

    List<RawJournalEntry> entries = journal.readLast(key, 1).toList();
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp()).isNotNull();
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }
}
