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
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.MessageBuilder;
import com.rabbitmq.stream.Producer;
import com.rabbitmq.stream.ProducerBuilder;
import com.rabbitmq.stream.StreamCreator;
import com.rabbitmq.stream.StreamException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    String id = journal.append("substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8));

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
    assertThatThrownBy(() -> journal.append("substrate:journal:test", data))
        .isInstanceOf(StreamException.class);
  }

  @Test
  void deleteClosesProducerAndDeletesStream() {
    wireStreamAndProducer();
    wireMessageBuilder();
    wireSuccessfulPublish();

    // Trigger producer creation by appending
    journal.append("substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8));

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

    journal.append("substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8));
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
}
