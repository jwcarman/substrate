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
package org.jwcarman.substrate.nats.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueOperation;
import io.nats.client.api.KeyValueStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NatsAtomSpiTest {

  @Mock private Connection connection;
  @Mock private KeyValueManagement kvm;

  @Test
  void atomKeyUsesConfiguredPrefix() throws Exception {
    wireConnectionForConstruction();
    NatsAtomSpi atom = createAtom();
    assertThat(atom.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void createStoresEncodedPayload() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    NatsAtomSpi atom = createAtom();

    atom.create(
        "substrate:atom:test",
        "hello".getBytes(StandardCharsets.UTF_8),
        "tok1",
        Duration.ofMinutes(5));

    verify(kv).create(anyString(), any(byte[].class));
  }

  @Test
  void createThrowsAtomAlreadyExistsOnKeyExists() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(10071);
    var kv = mockKeyValue();
    when(kv.create(anyString(), any(byte[].class))).thenThrow(apiException);

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(
            () ->
                atom.create(
                    "substrate:atom:test",
                    "hello".getBytes(StandardCharsets.UTF_8),
                    "tok1",
                    Duration.ofMinutes(5)))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void createThrowsIllegalStateOnOtherApiError() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(500);
    var kv = mockKeyValue();
    when(kv.create(anyString(), any(byte[].class))).thenThrow(apiException);

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(
            () ->
                atom.create(
                    "substrate:atom:test",
                    "hello".getBytes(StandardCharsets.UTF_8),
                    "tok1",
                    Duration.ofMinutes(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to create atom in NATS KV");
  }

  @Test
  void createThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    when(kv.create(anyString(), any(byte[].class))).thenThrow(new IOException("write failed"));

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(
            () ->
                atom.create(
                    "substrate:atom:test",
                    "hello".getBytes(StandardCharsets.UTF_8),
                    "tok1",
                    Duration.ofMinutes(5)))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to create atom in NATS KV");
  }

  @Test
  void readReturnsValueWhenPresent() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    byte[] payload = NatsAtomSpi.encode("hello".getBytes(StandardCharsets.UTF_8), "tok1");
    KeyValueEntry entry = mockEntry(payload, KeyValueOperation.PUT, 1L);
    when(kv.get(anyString())).thenReturn(entry);

    NatsAtomSpi atom = createAtom();
    Optional<RawAtom> result = atom.read("substrate:atom:test");

    assertThat(result).isPresent();
    assertThat(result.get().token()).isEqualTo("tok1");
    assertThat(new String(result.get().value(), StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void readReturnsEmptyWhenAbsent() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    when(kv.get(anyString())).thenReturn(null);

    NatsAtomSpi atom = createAtom();
    assertThat(atom.read("substrate:atom:test")).isEmpty();
  }

  @Test
  void readReturnsEmptyWhenDeleted() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    KeyValueEntry entry = mockEntry(null, KeyValueOperation.DELETE, 1L);
    when(kv.get(anyString())).thenReturn(entry);

    NatsAtomSpi atom = createAtom();
    assertThat(atom.read("substrate:atom:test")).isEmpty();
  }

  @Test
  void readThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    when(kv.get(anyString())).thenThrow(new IOException("read failed"));

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(() -> atom.read("substrate:atom:test"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to read atom from NATS KV");
  }

  @Test
  void readThrowsIllegalStateOnApiError() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(500);
    var kv = mockKeyValue();
    when(kv.get(anyString())).thenThrow(apiException);

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(() -> atom.read("substrate:atom:test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to read atom from NATS KV");
  }

  @Test
  void setUpdatesWithRevision() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    byte[] payload = NatsAtomSpi.encode("old".getBytes(StandardCharsets.UTF_8), "tok1");
    KeyValueEntry entry = mockEntry(payload, KeyValueOperation.PUT, 5L);
    when(kv.get(anyString())).thenReturn(entry);

    NatsAtomSpi atom = createAtom();
    boolean result =
        atom.set(
            "substrate:atom:test",
            "new".getBytes(StandardCharsets.UTF_8),
            "tok2",
            Duration.ofMinutes(5));

    assertThat(result).isTrue();
    verify(kv).update(anyString(), any(byte[].class), anyLong());
  }

  @Test
  void setReturnsFalseWhenAbsent() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    when(kv.get(anyString())).thenReturn(null);

    NatsAtomSpi atom = createAtom();
    assertThat(
            atom.set(
                "substrate:atom:test",
                "new".getBytes(StandardCharsets.UTF_8),
                "tok2",
                Duration.ofMinutes(5)))
        .isFalse();
  }

  @Test
  void setReturnsFalseOnRevisionConflict() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(10071);
    var kv = mockKeyValue();
    byte[] payload = NatsAtomSpi.encode("old".getBytes(StandardCharsets.UTF_8), "tok1");
    KeyValueEntry entry = mockEntry(payload, KeyValueOperation.PUT, 5L);
    when(kv.get(anyString())).thenReturn(entry);
    when(kv.update(anyString(), any(byte[].class), anyLong())).thenThrow(apiException);

    NatsAtomSpi atom = createAtom();
    assertThat(
            atom.set(
                "substrate:atom:test",
                "new".getBytes(StandardCharsets.UTF_8),
                "tok2",
                Duration.ofMinutes(5)))
        .isFalse();
  }

  @Test
  void setThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    byte[] payload = NatsAtomSpi.encode("old".getBytes(StandardCharsets.UTF_8), "tok1");
    KeyValueEntry entry = mockEntry(payload, KeyValueOperation.PUT, 5L);
    when(kv.get(anyString())).thenReturn(entry);
    when(kv.update(anyString(), any(byte[].class), anyLong()))
        .thenThrow(new IOException("write failed"));

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(
            () ->
                atom.set(
                    "substrate:atom:test",
                    "new".getBytes(StandardCharsets.UTF_8),
                    "tok2",
                    Duration.ofMinutes(5)))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to set atom in NATS KV");
  }

  @Test
  void touchReturnsTrueWhenPresent() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    byte[] payload = NatsAtomSpi.encode("hello".getBytes(StandardCharsets.UTF_8), "tok1");
    KeyValueEntry entry = mockEntry(payload, KeyValueOperation.PUT, 3L);
    when(kv.get(anyString())).thenReturn(entry);

    NatsAtomSpi atom = createAtom();
    assertThat(atom.touch("substrate:atom:test", Duration.ofMinutes(10))).isTrue();
    verify(kv).update(anyString(), any(byte[].class), anyLong());
  }

  @Test
  void touchReturnsFalseWhenAbsent() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    when(kv.get(anyString())).thenReturn(null);

    NatsAtomSpi atom = createAtom();
    assertThat(atom.touch("substrate:atom:test", Duration.ofMinutes(10))).isFalse();
  }

  @Test
  void touchReturnsFalseOnRevisionConflict() throws Exception {
    wireConnectionForConstruction();
    JetStreamApiException apiException = mockApiException(10071);
    var kv = mockKeyValue();
    byte[] payload = NatsAtomSpi.encode("hello".getBytes(StandardCharsets.UTF_8), "tok1");
    KeyValueEntry entry = mockEntry(payload, KeyValueOperation.PUT, 3L);
    when(kv.get(anyString())).thenReturn(entry);
    when(kv.update(anyString(), any(byte[].class), anyLong())).thenThrow(apiException);

    NatsAtomSpi atom = createAtom();
    assertThat(atom.touch("substrate:atom:test", Duration.ofMinutes(10))).isFalse();
  }

  @Test
  void touchThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    byte[] payload = NatsAtomSpi.encode("hello".getBytes(StandardCharsets.UTF_8), "tok1");
    KeyValueEntry entry = mockEntry(payload, KeyValueOperation.PUT, 3L);
    when(kv.get(anyString())).thenReturn(entry);
    when(kv.update(anyString(), any(byte[].class), anyLong()))
        .thenThrow(new IOException("write failed"));

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(() -> atom.touch("substrate:atom:test", Duration.ofMinutes(10)))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to touch atom in NATS KV");
  }

  @Test
  void deleteCallsKvDelete() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    NatsAtomSpi atom = createAtom();

    atom.delete("substrate:atom:test");

    verify(kv).delete(anyString());
  }

  @Test
  void deleteThrowsUncheckedIOExceptionOnIOError() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    doThrow(new IOException("delete failed")).when(kv).delete(anyString());

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(() -> atom.delete("substrate:atom:test"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to delete atom from NATS KV");
  }

  @Test
  void deleteThrowsIllegalStateOnApiError() throws Exception {
    wireConnectionForConstruction();
    var kv = mockKeyValue();
    JetStreamApiException apiException = mockApiException(500);
    doThrow(apiException).when(kv).delete(anyString());

    NatsAtomSpi atom = createAtom();
    assertThatThrownBy(() -> atom.delete("substrate:atom:test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to delete atom from NATS KV");
  }

  @Test
  void sweepReturnsZero() throws Exception {
    wireConnectionForConstruction();
    NatsAtomSpi atom = createAtom();
    assertThat(atom.sweep(100)).isZero();
  }

  @Test
  void encodeDecodeRoundTrip() {
    byte[] value = "test-value".getBytes(StandardCharsets.UTF_8);
    String token = "test-token";
    byte[] encoded = NatsAtomSpi.encode(value, token);
    RawAtom decoded = NatsAtomSpi.decode(encoded);

    assertThat(decoded.token()).isEqualTo(token);
    assertThat(decoded.value()).isEqualTo(value);
  }

  private void wireConnectionForConstruction() throws Exception {
    when(connection.keyValueManagement()).thenReturn(kvm);
    when(kvm.getStatus("substrate-atoms")).thenReturn(mock(KeyValueStatus.class));
  }

  private KeyValue mockKeyValue() throws Exception {
    var kv = mock(KeyValue.class);
    when(connection.keyValue("substrate-atoms")).thenReturn(kv);
    return kv;
  }

  private NatsAtomSpi createAtom() {
    return new NatsAtomSpi(connection, "substrate:atom:", "substrate-atoms", Duration.ofMinutes(5));
  }

  private KeyValueEntry mockEntry(byte[] value, KeyValueOperation operation, long revision) {
    KeyValueEntry entry = mock(KeyValueEntry.class);
    lenient().when(entry.getValue()).thenReturn(value);
    lenient().when(entry.getOperation()).thenReturn(operation);
    lenient().when(entry.getRevision()).thenReturn(revision);
    return entry;
  }

  private JetStreamApiException mockApiException(int apiErrorCode) {
    io.nats.client.api.Error error = mock(io.nats.client.api.Error.class);
    lenient().when(error.getCode()).thenReturn(apiErrorCode);
    lenient().when(error.getApiErrorCode()).thenReturn(apiErrorCode);
    lenient().when(error.getDescription()).thenReturn("test error");
    return new JetStreamApiException(error);
  }
}
