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
package org.jwcarman.substrate.journal.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.stream.Environment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Testcontainers
class RabbitMqJournalSpiIT {

  @Container
  static RabbitMQContainer rabbitMQContainer =
      new RabbitMQContainer("rabbitmq:4-management")
          .withExposedPorts(5552, 5672, 15672)
          .withCopyToContainer(
              Transferable.of("[rabbitmq_management,rabbitmq_stream]."),
              "/etc/rabbitmq/enabled_plugins");

  private Environment environment;
  private RabbitMqJournalSpi journal;

  @BeforeEach
  void setUp() {
    environment =
        Environment.builder()
            .host(rabbitMQContainer.getHost())
            .port(rabbitMQContainer.getMappedPort(5552))
            .build();

    journal =
        new RabbitMqJournalSpi(environment, "substrate:journal:", Duration.ofHours(1), 524288000L);
  }

  @AfterEach
  void tearDown() {
    if (journal != null) {
      journal.close();
    }
    if (environment != null) {
      environment.close();
    }
  }

  @Test
  void appendReturnsUuidV7Id() {
    String key = journal.journalKey("append-test-" + System.nanoTime());
    String id = journal.append(key, "hello".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }

  @Test
  void appendReturnsMonotonicallyIncreasingIds() {
    String key = journal.journalKey("mono-" + System.nanoTime());
    String id1 = journal.append(key, "first".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    String id2 =
        journal.append(key, "second".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    assertThat(id2).isGreaterThan(id1);
  }

  @Test
  void readAfterReturnsEntriesInOrder() {
    String key = journal.journalKey("read-after-" + System.nanoTime());
    String id1 =
        journal.append(key, "payload1".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    String id2 =
        journal.append(key, "payload2".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    String id3 =
        journal.append(key, "payload3".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readAfter(key, id1);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo(id2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("payload2");
    assertThat(entries.get(1).id()).isEqualTo(id3);
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("payload3");
  }

  @Test
  void readAfterReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent-" + System.nanoTime());
    List<RawJournalEntry> entries = journal.readAfter(key, "0");
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsLastNInChronologicalOrder() {
    String key = journal.journalKey("read-last-" + System.nanoTime());
    journal.append(key, "first".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key, "second".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key, "third".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key, "fourth".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readLast(key, 2);

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("third");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("fourth");
  }

  @Test
  void readLastReturnsEmptyForUnknownKey() {
    String key = journal.journalKey("nonexistent-" + System.nanoTime());
    List<RawJournalEntry> entries = journal.readLast(key, 5);
    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsAllWhenCountExceedsSize() {
    String key = journal.journalKey("small-" + System.nanoTime());
    journal.append(key, "one".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key, "two".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readLast(key, 100);

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("one");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("two");
  }

  @Test
  void completeDoesNotAppearInReadResults() {
    String key = journal.journalKey("complete-" + System.nanoTime());
    journal.append(key, "data1".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key, "data2".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key);

    List<RawJournalEntry> entries = journal.readLast(key, 100);

    assertThat(entries).hasSize(2);
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("data1");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("data2");
  }

  @Test
  void deleteRemovesStream() {
    String key = journal.journalKey("delete-" + System.nanoTime());
    journal.append(key, "hello".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key, "world".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    journal.delete(key);

    List<RawJournalEntry> entries = journal.readLast(key, 100);
    assertThat(entries).isEmpty();
  }

  @Test
  void deleteDoesNotAffectOtherJournals() {
    String key1 = journal.journalKey("a-" + System.nanoTime());
    String key2 = journal.journalKey("b-" + System.nanoTime());
    journal.append(key1, "a-event".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.append(key2, "b-event".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    journal.delete(key1);

    assertThat(journal.readLast(key1, 100)).isEmpty();
    assertThat(journal.readLast(key2, 100)).hasSize(1);
  }

  @Test
  void timestampIsPreserved() {
    String key = journal.journalKey("time-" + System.nanoTime());
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    List<RawJournalEntry> entries = journal.readLast(key, 1);
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp()).isNotNull();
  }

  @Test
  void isCompleteReturnsFalseForNonCompletedJournal() {
    String key = journal.journalKey("incomplete-" + System.nanoTime());
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    assertThat(journal.isComplete(key)).isFalse();
  }

  @Test
  void isCompleteReturnsTrueAfterComplete() {
    String key = journal.journalKey("is-complete-" + System.nanoTime());
    journal.append(key, "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.complete(key);

    assertThat(journal.isComplete(key)).isTrue();
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }
}
