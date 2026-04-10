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
package org.jwcarman.substrate.nats.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.Error;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NatsMailboxSpiTest {

  @Mock private Connection connection;
  @Mock private KeyValueManagement kvm;

  @Test
  void mailboxKeyUsesConfiguredPrefix() throws Exception {
    wireConnectionForConstruction();

    NatsMailboxSpi mailbox = createMailbox();
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }

  @Test
  void deliverPutsValue() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);
    KeyValueEntry entry = mock(KeyValueEntry.class);
    when(kv.get(anyString())).thenReturn(entry);

    NatsMailboxSpi mailbox = createMailbox();
    mailbox.deliver("substrate:mailbox:test", "hello".getBytes(StandardCharsets.UTF_8));

    verify(kv).put(anyString(), any(byte[].class));
  }

  @Test
  void getReturnsValueWhenPresent() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);

    KeyValueEntry entry = mock(KeyValueEntry.class);
    when(entry.getValue()).thenReturn("existing-value".getBytes(StandardCharsets.UTF_8));
    when(kv.get(anyString())).thenReturn(entry);

    NatsMailboxSpi mailbox = createMailbox();
    Optional<byte[]> result = mailbox.get("substrate:mailbox:test");

    assertThat(result).contains("existing-value".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getThrowsWhenAbsent() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);

    when(kv.get(anyString())).thenReturn(null);

    NatsMailboxSpi mailbox = createMailbox();
    assertThrows(MailboxExpiredException.class, () -> mailbox.get("substrate:mailbox:test"));
  }

  @Test
  void getReturnsEmptyWhenCreatedButNotDelivered() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);

    KeyValueEntry entry = mock(KeyValueEntry.class);
    when(entry.getValue()).thenReturn(new byte[0]);
    when(kv.get(anyString())).thenReturn(entry);

    NatsMailboxSpi mailbox = createMailbox();
    Optional<byte[]> result = mailbox.get("substrate:mailbox:test");

    assertThat(result).isEmpty();
  }

  @Test
  void deliverThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);
    KeyValueEntry entry = mock(KeyValueEntry.class);
    when(kv.get(anyString())).thenReturn(entry);
    when(kv.put(anyString(), any(byte[].class))).thenThrow(new IOException("write failed"));

    NatsMailboxSpi mailbox = createMailbox();
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> mailbox.deliver("substrate:mailbox:test", data))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to deliver to NATS KV");
  }

  @Test
  void deliverThrowsIllegalStateExceptionOnJetStreamApiError() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);
    KeyValueEntry entry = mock(KeyValueEntry.class);
    when(kv.get(anyString())).thenReturn(entry);
    when(kv.put(anyString(), any(byte[].class))).thenThrow(apiException);

    NatsMailboxSpi mailbox = createMailbox();
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> mailbox.deliver("substrate:mailbox:test", data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to deliver to NATS KV");
  }

  @Test
  void getThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);
    when(kv.get(anyString())).thenThrow(new IOException("read failed"));

    NatsMailboxSpi mailbox = createMailbox();
    assertThatThrownBy(() -> mailbox.get("substrate:mailbox:test"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to get from NATS KV");
  }

  @Test
  void getThrowsIllegalStateExceptionOnJetStreamApiError() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);
    when(kv.get(anyString())).thenThrow(apiException);

    NatsMailboxSpi mailbox = createMailbox();
    assertThatThrownBy(() -> mailbox.get("substrate:mailbox:test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to get from NATS KV");
  }

  @Test
  void deleteThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);
    doThrow(new IOException("delete failed")).when(kv).delete(anyString());

    NatsMailboxSpi mailbox = createMailbox();
    assertThatThrownBy(() -> mailbox.delete("substrate:mailbox:test"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to delete from NATS KV");
  }

  @Test
  void deleteThrowsIllegalStateExceptionOnJetStreamApiError() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);
    JetStreamApiException apiException = mockApiException();
    doThrow(apiException).when(kv).delete(anyString());

    NatsMailboxSpi mailbox = createMailbox();
    assertThatThrownBy(() -> mailbox.delete("substrate:mailbox:test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to delete from NATS KV");
  }

  private void wireConnectionForConstruction() throws Exception {
    when(connection.keyValueManagement()).thenReturn(kvm);
    when(kvm.getStatus("substrate-mailbox")).thenReturn(mock(KeyValueStatus.class));
  }

  private NatsMailboxSpi createMailbox() {
    return new NatsMailboxSpi(
        connection, "substrate:mailbox:", "substrate-mailbox", Duration.ofMinutes(5));
  }

  private JetStreamApiException mockApiException() {
    Error error = mock(Error.class);
    lenient().when(error.getCode()).thenReturn(500);
    lenient().when(error.getApiErrorCode()).thenReturn(500);
    lenient().when(error.getDescription()).thenReturn("test error");
    return new JetStreamApiException(error);
  }
}
