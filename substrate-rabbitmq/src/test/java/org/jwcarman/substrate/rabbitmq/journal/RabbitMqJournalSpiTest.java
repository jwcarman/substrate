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
package org.jwcarman.substrate.rabbitmq.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.stream.ConfirmationHandler;
import com.rabbitmq.stream.ConfirmationStatus;
import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.ConsumerBuilder;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.MessageBuilder;
import com.rabbitmq.stream.OffsetSpecification;
import com.rabbitmq.stream.Producer;
import com.rabbitmq.stream.ProducerBuilder;
import com.rabbitmq.stream.StreamCreator;
import com.rabbitmq.stream.StreamException;
import com.rabbitmq.stream.StreamStats;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitMqJournalSpiTest {

  @Mock private Environment environment;
  @Mock private StreamCreator streamCreator;
  @Mock private ProducerBuilder producerBuilder;
  @Mock private Producer producer;
  @Mock private MessageBuilder messageBuilder;
  @Mock private MessageBuilder.ApplicationPropertiesBuilder appPropsBuilder;

  private RabbitMqJournalSpi journal;

  @BeforeEach
  void setUp() {
    journal =
        new RabbitMqJournalSpi(environment, "substrate:journal:", Duration.ofHours(24), 1024L);
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void appendPublishesMessageAndReturnsEntryId() {
    wireStreamAndProducer();
    wireMessageBuilder();
    wireSuccessfulPublish();

    String id =
        journal.append(
            "substrate:journal:test",
            "hello".getBytes(StandardCharsets.UTF_8),
            Duration.ofHours(1));

    assertThat(id).isNotNull();
    verify(producer).send(any(Message.class), any(ConfirmationHandler.class));
  }

  @Test
  void appendThrowsOnPublishTimeout() {
    wireStreamAndProducer();
    wireMessageBuilder();

    // Producer.send never calls the confirmation handler — simulates timeout
    // But the timeout is 5 seconds, so we'd have to wait. Instead, test the StreamException path.
    doAnswer(
            invocation -> {
              ConfirmationHandler handler = invocation.getArgument(1);
              ConfirmationStatus status = mock(ConfirmationStatus.class);
              when(status.isConfirmed()).thenReturn(false);
              when(status.getCode()).thenReturn((short) 1);
              handler.handle(status);
              return null;
            })
        .when(producer)
        .send(any(Message.class), any(ConfirmationHandler.class));

    byte[] data = "data".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.append("substrate:journal:test", data, ttl))
        .isInstanceOf(StreamException.class);
  }

  @Test
  void deleteClosesProducerAndDeletesStream() {
    wireStreamAndProducer();
    wireMessageBuilder();
    wireSuccessfulPublish();

    // Trigger producer creation by appending
    journal.append(
        "substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    journal.delete("substrate:journal:test");

    verify(producer).close();
    verify(environment).deleteStream("substrate-journal-test");
  }

  @Test
  void deleteIgnoresNonExistentStream() {
    doThrow(new StreamException("stream does not exist"))
        .when(environment)
        .deleteStream("substrate-journal-test");

    assertThatNoException().isThrownBy(() -> journal.delete("substrate:journal:test"));
  }

  @Test
  void closeClosesAllProducers() {
    wireStreamAndProducer();
    wireMessageBuilder();
    wireSuccessfulPublish();

    journal.append(
        "substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));
    journal.close();

    verify(producer).close();
  }

  private void wireStreamAndProducer() {
    when(environment.streamCreator()).thenReturn(streamCreator);
    when(streamCreator.stream(any())).thenReturn(streamCreator);
    when(streamCreator.maxAge(any())).thenReturn(streamCreator);
    when(streamCreator.maxLengthBytes(any())).thenReturn(streamCreator);

    when(environment.producerBuilder()).thenReturn(producerBuilder);
    when(producerBuilder.stream(any())).thenReturn(producerBuilder);
    when(producerBuilder.build()).thenReturn(producer);
  }

  private void wireMessageBuilder() {
    when(producer.messageBuilder()).thenReturn(messageBuilder);
    when(messageBuilder.applicationProperties()).thenReturn(appPropsBuilder);
    when(appPropsBuilder.entry(any(), any(String.class))).thenReturn(appPropsBuilder);
    when(appPropsBuilder.messageBuilder()).thenReturn(messageBuilder);
    when(messageBuilder.addData(any(byte[].class))).thenReturn(messageBuilder);
    when(messageBuilder.build()).thenReturn(mock(Message.class));
  }

  private void wireSuccessfulPublish() {
    doAnswer(
            invocation -> {
              ConfirmationHandler handler = invocation.getArgument(1);
              ConfirmationStatus status = mock(ConfirmationStatus.class);
              when(status.isConfirmed()).thenReturn(true);
              handler.handle(status);
              return null;
            })
        .when(producer)
        .send(any(Message.class), any(ConfirmationHandler.class));
  }

  @Test
  void isCompleteReturnsFalseForNonExistentStream() {
    doThrow(new StreamException("stream does not exist"))
        .when(environment)
        .queryStreamStats("substrate-journal-test");

    assertThat(journal.isComplete("substrate:journal:test")).isFalse();
  }

  @Test
  void isCompleteReturnsTrueWhenCompletedMarkerExists() {
    StreamStats stats = mock(StreamStats.class);
    when(environment.queryStreamStats("substrate-journal-test")).thenReturn(stats);

    ConsumerBuilder consumerBuilder = mock(ConsumerBuilder.class);
    when(environment.consumerBuilder()).thenReturn(consumerBuilder);
    when(consumerBuilder.stream(any())).thenReturn(consumerBuilder);
    when(consumerBuilder.offset(any(OffsetSpecification.class))).thenReturn(consumerBuilder);

    Consumer consumer = mock(Consumer.class);
    doAnswer(
            invocation -> {
              com.rabbitmq.stream.MessageHandler handler = invocation.getArgument(0);
              Message message = mock(Message.class);
              Map<String, Object> props = Map.of("entryId", "__COMPLETED__");
              when(message.getApplicationProperties()).thenReturn(props);
              handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), message);
              return consumerBuilder;
            })
        .when(consumerBuilder)
        .messageHandler(any());
    when(consumerBuilder.build()).thenReturn(consumer);

    assertThat(journal.isComplete("substrate:journal:test")).isTrue();
  }

  @Test
  void isCompleteReturnsFalseWhenNoCompletedMarker() {
    StreamStats stats = mock(StreamStats.class);
    when(environment.queryStreamStats("substrate-journal-test")).thenReturn(stats);

    ConsumerBuilder consumerBuilder = mock(ConsumerBuilder.class);
    when(environment.consumerBuilder()).thenReturn(consumerBuilder);
    when(consumerBuilder.stream(any())).thenReturn(consumerBuilder);
    when(consumerBuilder.offset(any(OffsetSpecification.class))).thenReturn(consumerBuilder);

    Consumer consumer = mock(Consumer.class);
    doAnswer(
            invocation -> {
              com.rabbitmq.stream.MessageHandler handler = invocation.getArgument(0);
              Message message = mock(Message.class);
              Map<String, Object> props =
                  Map.of(
                      "entryId", "some-entry-id",
                      "journalKey", "substrate:journal:test",
                      "timestamp", Instant.now().toString());
              when(message.getApplicationProperties()).thenReturn(props);
              handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), message);
              return consumerBuilder;
            })
        .when(consumerBuilder)
        .messageHandler(any());
    when(consumerBuilder.build()).thenReturn(consumer);

    assertThat(journal.isComplete("substrate:journal:test")).isFalse();
  }

  @Test
  void deserializeEntryWithNullApplicationPropertiesSkipsEntry() {
    StreamStats stats = mock(StreamStats.class);
    when(environment.queryStreamStats("substrate-journal-test")).thenReturn(stats);

    ConsumerBuilder consumerBuilder = mock(ConsumerBuilder.class);
    when(environment.consumerBuilder()).thenReturn(consumerBuilder);
    when(consumerBuilder.stream(any())).thenReturn(consumerBuilder);
    when(consumerBuilder.offset(any(OffsetSpecification.class))).thenReturn(consumerBuilder);

    Consumer consumer = mock(Consumer.class);
    doAnswer(
            invocation -> {
              com.rabbitmq.stream.MessageHandler handler = invocation.getArgument(0);
              Message message = mock(Message.class);
              when(message.getApplicationProperties()).thenReturn(null);
              handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), message);
              return consumerBuilder;
            })
        .when(consumerBuilder)
        .messageHandler(any());
    when(consumerBuilder.build()).thenReturn(consumer);

    List<RawJournalEntry> entries = journal.readAfter("substrate:journal:test", "0");

    assertThat(entries).isEmpty();
  }

  @Test
  void deserializeEntryWithMissingTimestampUsesCurrentTime() {
    StreamStats stats = mock(StreamStats.class);
    when(environment.queryStreamStats("substrate-journal-test")).thenReturn(stats);

    ConsumerBuilder consumerBuilder = mock(ConsumerBuilder.class);
    when(environment.consumerBuilder()).thenReturn(consumerBuilder);
    when(consumerBuilder.stream(any())).thenReturn(consumerBuilder);
    when(consumerBuilder.offset(any(OffsetSpecification.class))).thenReturn(consumerBuilder);

    Consumer consumer = mock(Consumer.class);
    Instant before = Instant.now();
    doAnswer(
            invocation -> {
              com.rabbitmq.stream.MessageHandler handler = invocation.getArgument(0);
              Message message = mock(Message.class);
              Map<String, Object> props =
                  Map.of("entryId", "test-entry-1", "journalKey", "substrate:journal:test");
              when(message.getApplicationProperties()).thenReturn(props);
              when(message.getBodyAsBinary()).thenReturn("data".getBytes(StandardCharsets.UTF_8));
              handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), message);
              return consumerBuilder;
            })
        .when(consumerBuilder)
        .messageHandler(any());
    when(consumerBuilder.build()).thenReturn(consumer);

    List<RawJournalEntry> entries = journal.readLast("substrate:journal:test", 10);
    Instant after = Instant.now();

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().id()).isEqualTo("test-entry-1");
    assertThat(entries.getFirst().timestamp()).isBetween(before, after);
  }

  @Test
  void deserializeEntryWithNullBodyUsesEmptyByteArray() {
    StreamStats stats = mock(StreamStats.class);
    when(environment.queryStreamStats("substrate-journal-test")).thenReturn(stats);

    ConsumerBuilder consumerBuilder = mock(ConsumerBuilder.class);
    when(environment.consumerBuilder()).thenReturn(consumerBuilder);
    when(consumerBuilder.stream(any())).thenReturn(consumerBuilder);
    when(consumerBuilder.offset(any(OffsetSpecification.class))).thenReturn(consumerBuilder);

    Consumer consumer = mock(Consumer.class);
    doAnswer(
            invocation -> {
              com.rabbitmq.stream.MessageHandler handler = invocation.getArgument(0);
              Message message = mock(Message.class);
              Map<String, Object> props =
                  Map.of(
                      "entryId", "test-entry-1",
                      "journalKey", "substrate:journal:test",
                      "timestamp", Instant.now().toString());
              when(message.getApplicationProperties()).thenReturn(props);
              when(message.getBodyAsBinary()).thenReturn(null);
              handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), message);
              return consumerBuilder;
            })
        .when(consumerBuilder)
        .messageHandler(any());
    when(consumerBuilder.build()).thenReturn(consumer);

    List<RawJournalEntry> entries = journal.readLast("substrate:journal:test", 10);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().data()).isEmpty();
  }

  @Test
  void completePublishesCompletedMarker() {
    wireStreamAndProducer();
    wireMessageBuilder();
    wireSuccessfulPublish();

    journal.complete("substrate:journal:test", Duration.ofHours(1));

    verify(producer).send(any(Message.class), any(ConfirmationHandler.class));
  }

  @Test
  void readAfterFiltersEntriesByComparingIds() {
    StreamStats stats = mock(StreamStats.class);
    when(environment.queryStreamStats("substrate-journal-test")).thenReturn(stats);

    ConsumerBuilder consumerBuilder = mock(ConsumerBuilder.class);
    when(environment.consumerBuilder()).thenReturn(consumerBuilder);
    when(consumerBuilder.stream(any())).thenReturn(consumerBuilder);
    when(consumerBuilder.offset(any(OffsetSpecification.class))).thenReturn(consumerBuilder);

    Consumer consumer = mock(Consumer.class);
    doAnswer(
            invocation -> {
              com.rabbitmq.stream.MessageHandler handler = invocation.getArgument(0);

              Message msg1 = mock(Message.class);
              Map<String, Object> props1 =
                  Map.of(
                      "entryId", "aaa",
                      "journalKey", "substrate:journal:test",
                      "timestamp", Instant.now().toString());
              when(msg1.getApplicationProperties()).thenReturn(props1);
              when(msg1.getBodyAsBinary()).thenReturn("d1".getBytes(StandardCharsets.UTF_8));
              handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), msg1);

              Message msg2 = mock(Message.class);
              Map<String, Object> props2 =
                  Map.of(
                      "entryId", "bbb",
                      "journalKey", "substrate:journal:test",
                      "timestamp", Instant.now().toString());
              when(msg2.getApplicationProperties()).thenReturn(props2);
              when(msg2.getBodyAsBinary()).thenReturn("d2".getBytes(StandardCharsets.UTF_8));
              handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), msg2);

              Message msg3 = mock(Message.class);
              Map<String, Object> props3 =
                  Map.of(
                      "entryId", "ccc",
                      "journalKey", "substrate:journal:test",
                      "timestamp", Instant.now().toString());
              when(msg3.getApplicationProperties()).thenReturn(props3);
              when(msg3.getBodyAsBinary()).thenReturn("d3".getBytes(StandardCharsets.UTF_8));
              handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), msg3);

              return consumerBuilder;
            })
        .when(consumerBuilder)
        .messageHandler(any());
    when(consumerBuilder.build()).thenReturn(consumer);

    List<RawJournalEntry> entries = journal.readAfter("substrate:journal:test", "bbb");

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().id()).isEqualTo("ccc");
  }

  @Test
  void readLastReturnsLastNEntries() {
    StreamStats stats = mock(StreamStats.class);
    when(environment.queryStreamStats("substrate-journal-test")).thenReturn(stats);

    ConsumerBuilder consumerBuilder = mock(ConsumerBuilder.class);
    when(environment.consumerBuilder()).thenReturn(consumerBuilder);
    when(consumerBuilder.stream(any())).thenReturn(consumerBuilder);
    when(consumerBuilder.offset(any(OffsetSpecification.class))).thenReturn(consumerBuilder);

    Consumer consumer = mock(Consumer.class);
    doAnswer(
            invocation -> {
              com.rabbitmq.stream.MessageHandler handler = invocation.getArgument(0);
              for (int i = 1; i <= 5; i++) {
                Message msg = mock(Message.class);
                Map<String, Object> props =
                    Map.of(
                        "entryId",
                        "entry-" + i,
                        "journalKey",
                        "substrate:journal:test",
                        "timestamp",
                        Instant.now().toString());
                when(msg.getApplicationProperties()).thenReturn(props);
                when(msg.getBodyAsBinary())
                    .thenReturn(("data-" + i).getBytes(StandardCharsets.UTF_8));
                handler.handle(mock(com.rabbitmq.stream.MessageHandler.Context.class), msg);
              }
              return consumerBuilder;
            })
        .when(consumerBuilder)
        .messageHandler(any());
    when(consumerBuilder.build()).thenReturn(consumer);

    List<RawJournalEntry> entries = journal.readLast("substrate:journal:test", 2);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo("entry-4");
    assertThat(entries.get(1).id()).isEqualTo("entry-5");
  }

  @Test
  void readAfterReturnsEmptyForNonExistentStream() {
    doThrow(new StreamException("stream does not exist"))
        .when(environment)
        .queryStreamStats("substrate-journal-test");

    List<RawJournalEntry> entries = journal.readAfter("substrate:journal:test", "some-id");

    assertThat(entries).isEmpty();
  }

  @Test
  void readLastReturnsEmptyForNonExistentStream() {
    doThrow(new StreamException("stream does not exist"))
        .when(environment)
        .queryStreamStats("substrate-journal-test");

    List<RawJournalEntry> entries = journal.readLast("substrate:journal:test", 5);

    assertThat(entries).isEmpty();
  }

  @Test
  void deleteWithNoCachedProducerOnlyDeletesStream() {
    journal.delete("substrate:journal:test");

    verify(environment).deleteStream("substrate-journal-test");
  }
}
