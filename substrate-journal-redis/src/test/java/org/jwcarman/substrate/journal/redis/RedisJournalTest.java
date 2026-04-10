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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisJournalSpiTest {

  @Mock private RedisCommands<String, String> commands;

  private RedisJournalSpi journal;

  @BeforeEach
  void setUp() {
    journal = new RedisJournalSpi(commands, "substrate:journal:", 100_000, Duration.ofHours(1));
  }

  @Test
  void appendPerformsXaddWithMaxlenTrimming() {
    when(commands.xadd(eq("substrate:journal:test"), any(XAddArgs.class), any(Map.class)))
        .thenReturn("1712404800000-0");
    when(commands.expire("substrate:journal:test", 3600)).thenReturn(true);

    String entryId =
        journal.append(
            "substrate:journal:test",
            "hello".getBytes(StandardCharsets.UTF_8),
            Duration.ofHours(1));

    assertThat(entryId).isEqualTo("1712404800000-0");

    verify(commands)
        .xadd(
            eq("substrate:journal:test"),
            any(XAddArgs.class),
            argThat(
                (Map<String, String> body) ->
                    Base64.getEncoder()
                            .encodeToString("hello".getBytes(StandardCharsets.UTF_8))
                            .equals(body.get("data"))
                        && body.containsKey("timestamp")));
  }

  @Test
  void appendRefreshesTtlViaExpire() {
    when(commands.xadd(eq("substrate:journal:test"), any(XAddArgs.class), any(Map.class)))
        .thenReturn("1-0");
    when(commands.expire("substrate:journal:test", 3600)).thenReturn(true);

    journal.append(
        "substrate:journal:test", "data".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    verify(commands).expire("substrate:journal:test", 3600);
  }

  @Test
  void appendWithCustomTtlUsesProvidedDuration() {
    when(commands.xadd(eq("substrate:journal:test"), any(XAddArgs.class), any(Map.class)))
        .thenReturn("1-0");
    when(commands.expire("substrate:journal:test", 300)).thenReturn(true);

    journal.append(
        "substrate:journal:test", "data".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5));

    verify(commands).expire("substrate:journal:test", 300);
  }

  @Test
  void readAfterReturnsEntriesAfterGivenId() {
    StreamMessage<String, String> msg1 =
        new StreamMessage<>(
            "substrate:journal:test",
            "2-0",
            Map.of(
                "data",
                Base64.getEncoder().encodeToString("first".getBytes(StandardCharsets.UTF_8)),
                "timestamp",
                "2026-04-06T12:00:00Z"));
    StreamMessage<String, String> msg2 =
        new StreamMessage<>(
            "substrate:journal:test",
            "3-0",
            Map.of(
                "data",
                Base64.getEncoder().encodeToString("second".getBytes(StandardCharsets.UTF_8)),
                "timestamp",
                "2026-04-06T12:01:00Z"));

    when(commands.xread(any(XReadArgs.class), any(XReadArgs.StreamOffset[].class)))
        .thenReturn(List.of(msg1, msg2));

    List<RawJournalEntry> entries = journal.readAfter("substrate:journal:test", "1-0");

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo("2-0");
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("first");
    assertThat(entries.get(0).key()).isEqualTo("substrate:journal:test");
    assertThat(entries.get(1).id()).isEqualTo("3-0");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("second");
  }

  @Test
  void readAfterReturnsEmptyStreamWhenNoMessages() {
    when(commands.xread(any(XReadArgs.class), any(XReadArgs.StreamOffset[].class)))
        .thenReturn(null);

    List<RawJournalEntry> result = journal.readAfter("substrate:journal:test", "0-0");

    assertThat(result).isEmpty();
  }

  @Test
  void readAfterReturnsEmptyWhenMessagesListIsEmpty() {
    when(commands.xread(any(XReadArgs.class), any(XReadArgs.StreamOffset[].class)))
        .thenReturn(List.of());

    List<RawJournalEntry> result = journal.readAfter("substrate:journal:test", "0-0");

    assertThat(result).isEmpty();
  }

  @Test
  void readLastReturnsEntriesInChronologicalOrder() {
    StreamMessage<String, String> msg2 =
        new StreamMessage<>(
            "substrate:journal:test",
            "2-0",
            Map.of(
                "data",
                Base64.getEncoder().encodeToString("second".getBytes(StandardCharsets.UTF_8)),
                "timestamp",
                "2026-04-06T12:01:00Z"));
    StreamMessage<String, String> msg1 =
        new StreamMessage<>(
            "substrate:journal:test",
            "1-0",
            Map.of(
                "data",
                Base64.getEncoder().encodeToString("first".getBytes(StandardCharsets.UTF_8)),
                "timestamp",
                "2026-04-06T12:00:00Z"));

    when(commands.xrevrange(eq("substrate:journal:test"), any(Range.class), any(Limit.class)))
        .thenReturn(List.of(msg2, msg1));

    List<RawJournalEntry> entries = journal.readLast("substrate:journal:test", 2);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo("1-0");
    assertThat(new String(entries.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("first");
    assertThat(entries.get(1).id()).isEqualTo("2-0");
    assertThat(new String(entries.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("second");
  }

  @Test
  void completeSetsCompletionFlag() {
    journal.complete("substrate:journal:test");

    verify(commands).set("substrate:journal:test:completed", "true");
  }

  @Test
  void deleteRemovesStreamAndCompletionFlag() {
    when(commands.del("substrate:journal:test", "substrate:journal:test:completed")).thenReturn(2L);

    journal.delete("substrate:journal:test");

    verify(commands).del("substrate:journal:test", "substrate:journal:test:completed");
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void toJournalEntryUsesInstantNowWhenTimestampIsMissing() {
    Map<String, String> body =
        Map.of(
            "data",
            Base64.getEncoder().encodeToString("test-data".getBytes(StandardCharsets.UTF_8)));
    StreamMessage<String, String> msg = new StreamMessage<>("substrate:journal:test", "1-0", body);

    when(commands.xread(any(XReadArgs.class), any(XReadArgs.StreamOffset[].class)))
        .thenReturn(List.of(msg));

    List<RawJournalEntry> entries = journal.readAfter("substrate:journal:test", "0-0");

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp()).isNotNull();
    assertThat(new String(entries.getFirst().data(), StandardCharsets.UTF_8))
        .isEqualTo("test-data");
  }
}
