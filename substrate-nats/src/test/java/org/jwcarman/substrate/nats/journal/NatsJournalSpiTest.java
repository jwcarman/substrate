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
package org.jwcarman.substrate.nats.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.KeyValueManagement;
import io.nats.client.PurgeOptions;
import io.nats.client.api.Error;
import io.nats.client.api.KeyValueStatus;
import io.nats.client.api.PublishAck;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.NatsMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
    String id =
        journal.append(
            "substrate:journal:test",
            "hello".getBytes(StandardCharsets.UTF_8),
            Duration.ofHours(1));

    assertThat(id).isEqualTo("42");
  }

  @Test
  void appendThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    when(jetStream.publish(any(NatsMessage.class))).thenThrow(new IOException("write failed"));

    NatsJournalSpi journal = createJournal();
    byte[] data = "data".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.append("substrate:journal:test", data, ttl))
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

  @Test
  void appendThrowsIllegalStateExceptionOnJetStreamApiError() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(500);
    when(jetStream.publish(any(NatsMessage.class))).thenThrow(apiException);

    NatsJournalSpi journal = createJournal();
    byte[] data = "data".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.append("substrate:journal:test", data, ttl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to publish to NATS JetStream");
  }

  @Test
  void completeThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    when(connection.keyValueManagement()).thenThrow(new IOException("kv failed"));

    NatsJournalSpi journal = createJournal();
    Duration ttl = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.complete("substrate:journal:test", ttl))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to mark journal as complete");
  }

  @Test
  void completeThrowsIllegalStateExceptionOnJetStreamApiError() throws Exception {
    wireConnectionForConstruction();
    var kvm = mock(KeyValueManagement.class);
    when(connection.keyValueManagement()).thenReturn(kvm);
    when(kvm.getStatus(anyString())).thenReturn(mock(KeyValueStatus.class));
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue(anyString())).thenReturn(kv);
    JetStreamApiException apiException = mockApiException(500);
    when(kv.put(anyString(), any(byte[].class))).thenThrow(apiException);

    NatsJournalSpi journal = createJournal();
    Duration ttl = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.complete("substrate:journal:test", ttl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to mark journal as complete");
  }

  @Test
  void isCompleteReturnsFalseOnIOException() throws Exception {
    wireConnectionForConstruction();
    when(connection.keyValueManagement()).thenThrow(new IOException("kv failed"));

    NatsJournalSpi journal = createJournal();
    assertThat(journal.isComplete("substrate:journal:test")).isFalse();
  }

  @Test
  void isCompleteReturnsFalseOnJetStreamApiException() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(500);
    var kvm = mock(KeyValueManagement.class);
    when(connection.keyValueManagement()).thenReturn(kvm);
    when(kvm.getStatus(anyString())).thenThrow(apiException);
    JetStreamApiException createException = mockApiException(500);
    when(kvm.create(any())).thenThrow(createException);

    NatsJournalSpi journal = createJournal();
    assertThat(journal.isComplete("substrate:journal:test")).isFalse();
  }

  @Test
  void deleteIgnoresIOException() throws Exception {
    wireConnectionForConstruction();
    when(jsm.purgeStream(anyString(), any(PurgeOptions.class)))
        .thenThrow(new IOException("purge failed"));

    NatsJournalSpi journal = createJournal();
    assertThatNoException().isThrownBy(() -> journal.delete("substrate:journal:test"));
  }

  @Test
  void deleteIgnoresJetStreamApiException() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(500);
    when(jsm.purgeStream(anyString(), any(PurgeOptions.class))).thenThrow(apiException);

    NatsJournalSpi journal = createJournal();
    assertThatNoException().isThrownBy(() -> journal.delete("substrate:journal:test"));
    // No exception thrown — JetStreamApiException is silently ignored
  }

  @Test
  void readAfterReturnsEmptyListOnIOException() throws Exception {
    wireConnectionForConstruction();
    when(jsm.getStreamInfo(anyString())).thenThrow(new IOException("stream info failed"));

    NatsJournalSpi journal = createJournal();
    List<?> result = journal.readAfter("substrate:journal:test", "1");
    assertThat(result).isEmpty();
  }

  @Test
  void readAfterReturnsEmptyListOnJetStreamApiException() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(500);
    when(jsm.getStreamInfo(anyString())).thenThrow(apiException);

    NatsJournalSpi journal = createJournal();
    List<?> result = journal.readAfter("substrate:journal:test", "1");
    assertThat(result).isEmpty();
  }

  @Test
  void readLastReturnsEmptyListOnIOException() throws Exception {
    wireConnectionForConstruction();
    when(jsm.getStreamInfo(anyString(), any())).thenThrow(new IOException("stream info failed"));

    NatsJournalSpi journal = createJournal();
    List<?> result = journal.readLast("substrate:journal:test", 10);
    assertThat(result).isEmpty();
  }

  @Test
  void readLastReturnsEmptyListOnJetStreamApiException() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(500);
    when(jsm.getStreamInfo(anyString(), any())).thenThrow(apiException);

    NatsJournalSpi journal = createJournal();
    List<?> result = journal.readLast("substrate:journal:test", 10);
    assertThat(result).isEmpty();
  }

  @Test
  void constructorThrowsIllegalStateOnNon10058ApiError() throws Exception {
    when(connection.jetStream()).thenReturn(jetStream);
    when(connection.jetStreamManagement()).thenReturn(jsm);
    JetStreamApiException apiException = mockApiException(500);
    when(jsm.addStream(any(StreamConfiguration.class))).thenThrow(apiException);

    assertThatThrownBy(this::createJournal)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to create NATS JetStream stream");
  }

  @Test
  void constructorThrowsUncheckedIOExceptionWhenUpdateStreamFailsWithIOException()
      throws Exception {
    when(connection.jetStream()).thenReturn(jetStream);
    when(connection.jetStreamManagement()).thenReturn(jsm);
    JetStreamApiException existsException = mockApiException(10058);
    when(jsm.addStream(any(StreamConfiguration.class))).thenThrow(existsException);
    when(jsm.updateStream(any(StreamConfiguration.class)))
        .thenThrow(new IOException("update failed"));

    assertThatThrownBy(this::createJournal)
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to update NATS JetStream stream");
  }

  @Test
  void constructorThrowsIllegalStateWhenUpdateStreamFailsWithApiException() throws Exception {
    when(connection.jetStream()).thenReturn(jetStream);
    when(connection.jetStreamManagement()).thenReturn(jsm);
    JetStreamApiException existsException = mockApiException(10058);
    when(jsm.addStream(any(StreamConfiguration.class))).thenThrow(existsException);
    JetStreamApiException updateException = mockApiException(999);
    when(jsm.updateStream(any(StreamConfiguration.class))).thenThrow(updateException);

    assertThatThrownBy(this::createJournal)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to update NATS JetStream stream");
  }

  @Test
  void isCompleteReturnsTrueWhenEntryExists() throws Exception {
    wireConnectionForConstruction();
    var kvm = mock(KeyValueManagement.class);
    when(connection.keyValueManagement()).thenReturn(kvm);
    when(kvm.getStatus(anyString())).thenReturn(mock(KeyValueStatus.class));
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue(anyString())).thenReturn(kv);
    var entry = mock(io.nats.client.api.KeyValueEntry.class);
    when(kv.get(anyString())).thenReturn(entry);

    NatsJournalSpi journal = createJournal();
    assertThat(journal.isComplete("substrate:journal:test")).isTrue();
  }

  @Test
  void completeSucceedsOnHappyPath() throws Exception {
    wireConnectionForConstruction();
    var kvm = mock(KeyValueManagement.class);
    when(connection.keyValueManagement()).thenReturn(kvm);
    when(kvm.getStatus(anyString())).thenReturn(mock(KeyValueStatus.class));
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue(anyString())).thenReturn(kv);

    NatsJournalSpi journal = createJournal();
    assertThatNoException()
        .isThrownBy(() -> journal.complete("substrate:journal:test", Duration.ofHours(1)));
    verify(kv).put(anyString(), any(byte[].class));
  }

  @Test
  void ensureCompletedBucketCreatesWhenNotFound() throws Exception {
    wireConnectionForConstruction();
    var kvm = mock(KeyValueManagement.class);
    when(connection.keyValueManagement()).thenReturn(kvm);
    JetStreamApiException notFound = mockApiException(404);
    when(kvm.getStatus(anyString())).thenThrow(notFound);
    when(kvm.create(any())).thenReturn(mock(KeyValueStatus.class));
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue(anyString())).thenReturn(kv);
    var entry = mock(io.nats.client.api.KeyValueEntry.class);
    when(kv.get(anyString())).thenReturn(entry);

    NatsJournalSpi journal = createJournal();
    assertThat(journal.isComplete("substrate:journal:test")).isTrue();
    verify(kvm).create(any());
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
