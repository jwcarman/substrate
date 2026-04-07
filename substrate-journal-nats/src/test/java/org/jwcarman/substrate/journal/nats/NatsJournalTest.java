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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.Error;
import io.nats.client.api.PublishAck;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.NatsMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NatsJournalSpiTest {

  @Mock private Connection connection;
  @Mock private JetStream jetStream;
  @Mock private JetStreamManagement jsm;
  @Mock private PublishAck publishAck;

  @Test
  void appendReturnsSequenceNumber() throws Exception {
    wireConnectionForConstruction();
    when(jetStream.publish(any(NatsMessage.class))).thenReturn(publishAck);
    when(publishAck.getSeqno()).thenReturn(42L);

    NatsJournalSpi journal = createJournal();
    String id = journal.append("substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8));

    assertThat(id).isEqualTo("42");
  }

  @Test
  void appendThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    when(jetStream.publish(any(NatsMessage.class))).thenThrow(new IOException("write failed"));

    NatsJournalSpi journal = createJournal();
    byte[] data = "data".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> journal.append("substrate:journal:test", data))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void journalKeyUsesConfiguredPrefix() throws Exception {
    wireConnectionForConstruction();

    NatsJournalSpi journal = createJournal();
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void constructorHandlesExistingStream() throws Exception {
    when(connection.jetStream()).thenReturn(jetStream);
    when(connection.jetStreamManagement()).thenReturn(jsm);
    JetStreamApiException existsException = mockApiException(10058);
    when(jsm.addStream(any(StreamConfiguration.class))).thenThrow(existsException);
    when(jsm.updateStream(any(StreamConfiguration.class))).thenReturn(null);

    NatsJournalSpi journal = createJournal();
    assertThat(journal).isNotNull();
    verify(jsm).updateStream(any(StreamConfiguration.class));
  }

  @Test
  void constructorThrowsOnIOException() throws Exception {
    when(connection.jetStream()).thenThrow(new IOException("connection failed"));

    assertThatThrownBy(this::createJournal).isInstanceOf(UncheckedIOException.class);
  }

  private void wireConnectionForConstruction() throws Exception {
    when(connection.jetStream()).thenReturn(jetStream);
    when(connection.jetStreamManagement()).thenReturn(jsm);
    lenient().when(jsm.addStream(any(StreamConfiguration.class))).thenReturn(null);
  }

  private NatsJournalSpi createJournal() {
    return new NatsJournalSpi(
        connection, "substrate:journal:", "substrate-journal", Duration.ofHours(24), 100000);
  }

  private JetStreamApiException mockApiException(int apiErrorCode) {
    Error error = mock(Error.class);
    lenient().when(error.getCode()).thenReturn(apiErrorCode);
    lenient().when(error.getApiErrorCode()).thenReturn(apiErrorCode);
    lenient().when(error.getDescription()).thenReturn("stream already exists");
    return new JetStreamApiException(error);
  }
}
