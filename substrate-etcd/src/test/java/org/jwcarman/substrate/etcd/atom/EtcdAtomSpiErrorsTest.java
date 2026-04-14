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
package org.jwcarman.substrate.etcd.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Txn;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.options.GetOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;

class EtcdAtomSpiErrorsTest {

  private Client client;
  private KV kv;
  private Lease lease;
  private EtcdAtomSpi atom;

  @BeforeEach
  void setUp() {
    client = mock(Client.class);
    kv = mock(KV.class);
    lease = mock(Lease.class);
    when(client.getKVClient()).thenReturn(kv);
    when(client.getLeaseClient()).thenReturn(lease);
    atom = new EtcdAtomSpi(client, "substrate:atom:");
  }

  private void stubGrantLease(long leaseId) {
    LeaseGrantResponse response = mock(LeaseGrantResponse.class);
    when(response.getID()).thenReturn(leaseId);
    when(lease.grant(anyLong())).thenReturn(CompletableFuture.completedFuture(response));
  }

  private void stubGrantLeaseFailure(Throwable cause) {
    CompletableFuture<LeaseGrantResponse> failed = new CompletableFuture<>();
    failed.completeExceptionally(cause);
    when(lease.grant(anyLong())).thenReturn(failed);
  }

  private Txn stubTxn(boolean succeeded) {
    Txn txn = mock(Txn.class, RETURNS_DEEP_STUBS);
    when(kv.txn()).thenReturn(txn);
    TxnResponse response = mock(TxnResponse.class);
    when(response.isSucceeded()).thenReturn(succeeded);
    when(txn.If(any()).Then(any()).commit())
        .thenReturn(CompletableFuture.completedFuture(response));
    return txn;
  }

  private void stubTxnFailure(Throwable cause) {
    Txn txn = mock(Txn.class, RETURNS_DEEP_STUBS);
    when(kv.txn()).thenReturn(txn);
    CompletableFuture<TxnResponse> failed = new CompletableFuture<>();
    failed.completeExceptionally(cause);
    when(txn.If(any()).Then(any()).commit()).thenReturn(failed);
  }

  private void stubGet(KeyValue... kvs) {
    GetResponse response = mock(GetResponse.class);
    when(response.getKvs()).thenReturn(List.of(kvs));
    when(kv.get(any(ByteSequence.class))).thenReturn(CompletableFuture.completedFuture(response));
    when(kv.get(any(ByteSequence.class), any(GetOption.class)))
        .thenReturn(CompletableFuture.completedFuture(response));
  }

  private void stubGetFailure(Throwable cause) {
    CompletableFuture<GetResponse> failed = new CompletableFuture<>();
    failed.completeExceptionally(cause);
    when(kv.get(any(ByteSequence.class))).thenReturn(failed);
    when(kv.get(any(ByteSequence.class), any(GetOption.class))).thenReturn(failed);
  }

  private KeyValue kv(long leaseId) {
    KeyValue kvEntry = mock(KeyValue.class);
    when(kvEntry.getValue())
        .thenReturn(ByteSequence.from(AtomPayload.encode(new byte[] {1, 2}, "tok")));
    when(kvEntry.getLease()).thenReturn(leaseId);
    return kvEntry;
  }

  @Test
  void createWrapsLeaseGrantExecutionFailure() {
    stubGrantLeaseFailure(new RuntimeException("grant boom"));

    assertThatThrownBy(
            () ->
                atom.create(
                    "substrate:atom:k",
                    "v".getBytes(StandardCharsets.UTF_8),
                    "tok",
                    Duration.ofSeconds(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to grant etcd lease");
  }

  @Test
  void createThrowsAlreadyExistsWhenTxnFails() {
    stubGrantLease(42L);
    stubTxn(false);

    assertThatThrownBy(
            () ->
                atom.create(
                    "substrate:atom:dup",
                    "v".getBytes(StandardCharsets.UTF_8),
                    "tok",
                    Duration.ofSeconds(5)))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void createWrapsTxnExecutionFailure() {
    stubGrantLease(42L);
    stubTxnFailure(new RuntimeException("txn boom"));

    assertThatThrownBy(
            () ->
                atom.create(
                    "substrate:atom:k",
                    "v".getBytes(StandardCharsets.UTF_8),
                    "tok",
                    Duration.ofSeconds(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to create atom in etcd");
  }

  @Test
  void readWrapsExecutionFailure() {
    stubGetFailure(new RuntimeException("read boom"));

    assertThatThrownBy(() -> atom.read("substrate:atom:k"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to read atom from etcd");
  }

  @Test
  void setReturnsFalseWhenKeyAbsent() {
    stubGet();

    boolean result =
        atom.set(
            "substrate:atom:k", "v".getBytes(StandardCharsets.UTF_8), "tok", Duration.ofSeconds(5));

    assertThat(result).isFalse();
  }

  @Test
  void setReturnsFalseWhenTxnFails() {
    stubGet(kv(7L));
    stubGrantLease(99L);
    stubTxn(false);
    doReturn(CompletableFuture.completedFuture(null)).when(lease).revoke(anyLong());

    boolean result =
        atom.set(
            "substrate:atom:k", "v".getBytes(StandardCharsets.UTF_8), "tok", Duration.ofSeconds(5));

    assertThat(result).isFalse();
  }

  @Test
  void setWrapsTxnExecutionFailure() {
    stubGet(kv(7L));
    stubGrantLease(99L);
    stubTxnFailure(new RuntimeException("txn boom"));

    assertThatThrownBy(
            () ->
                atom.set(
                    "substrate:atom:k",
                    "v".getBytes(StandardCharsets.UTF_8),
                    "tok",
                    Duration.ofSeconds(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to set atom in etcd");
  }

  @Test
  void touchReturnsFalseWhenKeyAbsent() {
    stubGet();

    assertThat(atom.touch("substrate:atom:k", Duration.ofSeconds(5))).isFalse();
  }

  @Test
  void touchReturnsTrueWhenCasLosesButKeyStillAlive() {
    stubGet(kv(7L));
    stubGrantLease(99L);
    stubTxn(false);
    doReturn(CompletableFuture.completedFuture(null)).when(lease).revoke(anyLong());

    // CAS failure means someone else wrote in between — key is alive with a fresh TTL.
    assertThat(atom.touch("substrate:atom:k", Duration.ofSeconds(5))).isTrue();
  }

  @Test
  void touchReturnsFalseWhenCasLosesAndKeyIsGone() {
    // First get() returns the key (for initial lookup); second get() returns empty
    // (atomExists() call after CAS loss) to simulate concurrent delete.
    KeyValue existing = kv(7L);
    List<KeyValue> presentKvs = List.of(existing);
    List<KeyValue> absentKvs = List.of();
    GetResponse present = mock(GetResponse.class);
    GetResponse absent = mock(GetResponse.class);
    when(present.getKvs()).thenReturn(presentKvs);
    when(absent.getKvs()).thenReturn(absentKvs);
    CompletableFuture<GetResponse> presentFuture = CompletableFuture.completedFuture(present);
    CompletableFuture<GetResponse> absentFuture = CompletableFuture.completedFuture(absent);
    when(kv.get(any(ByteSequence.class))).thenReturn(presentFuture, absentFuture);
    when(kv.get(any(ByteSequence.class), any(GetOption.class)))
        .thenReturn(presentFuture, absentFuture);
    stubGrantLease(99L);
    stubTxn(false);
    doReturn(CompletableFuture.completedFuture(null)).when(lease).revoke(anyLong());
    Duration ttl = Duration.ofSeconds(5);

    assertThat(atom.touch("substrate:atom:k", ttl)).isFalse();
  }

  @Test
  void touchWrapsTxnExecutionFailure() {
    stubGet(kv(7L));
    stubGrantLease(99L);
    stubTxnFailure(new RuntimeException("txn boom"));

    Duration ttl = Duration.ofSeconds(5);
    assertThatThrownBy(() -> atom.touch("substrate:atom:k", ttl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to touch atom in etcd");
  }

  @Test
  void setWrapsCurrentLeaseIdExecutionFailure() {
    stubGetFailure(new RuntimeException("lookup boom"));
    byte[] value = "v".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofSeconds(5);

    assertThatThrownBy(() -> atom.set("substrate:atom:k", value, "tok", ttl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to read atom from etcd");
  }

  @Test
  void touchWrapsCurrentKvExecutionFailure() {
    stubGetFailure(new RuntimeException("lookup boom"));
    Duration ttl = Duration.ofSeconds(5);

    assertThatThrownBy(() -> atom.touch("substrate:atom:k", ttl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to read atom from etcd");
  }

  @Test
  void deleteWrapsExecutionFailure() {
    CompletableFuture<io.etcd.jetcd.kv.DeleteResponse> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("delete boom"));
    when(kv.delete(any(ByteSequence.class))).thenReturn(failed);

    assertThatThrownBy(() -> atom.delete("substrate:atom:k"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to delete atom from etcd");
  }

  @Test
  void sweepReturnsZero() {
    assertThat(atom.sweep(100)).isZero();
  }

  @SuppressWarnings("unchecked")
  private static <T> CompletableFuture<T> interruptedFuture()
      throws InterruptedException, ExecutionException {
    CompletableFuture<T> future = mock(CompletableFuture.class);
    when(future.get()).thenThrow(new InterruptedException("test"));
    return future;
  }

  @Test
  void grantLeaseInterruptWrapsAndSetsInterrupt() throws Exception {
    CompletableFuture<LeaseGrantResponse> future = interruptedFuture();
    when(lease.grant(anyLong())).thenReturn(future);
    byte[] value = "v".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofSeconds(5);

    assertThatThrownBy(() -> atom.create("substrate:atom:k", value, "tok", ttl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Interrupted while granting etcd lease");
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void readInterruptWrapsAndSetsInterrupt() throws Exception {
    CompletableFuture<GetResponse> future = interruptedFuture();
    when(kv.get(any(ByteSequence.class))).thenReturn(future);

    assertThatThrownBy(() -> atom.read("substrate:atom:k"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Interrupted while reading atom from etcd");
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void deleteInterruptWrapsAndSetsInterrupt() throws Exception {
    CompletableFuture<io.etcd.jetcd.kv.DeleteResponse> future = interruptedFuture();
    when(kv.delete(any(ByteSequence.class))).thenReturn(future);

    assertThatThrownBy(() -> atom.delete("substrate:atom:k"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Interrupted while deleting atom from etcd");
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void setInterruptWrapsAndSetsInterrupt() throws Exception {
    stubGet(kv(7L));
    stubGrantLease(99L);
    Txn txn = mock(Txn.class, RETURNS_DEEP_STUBS);
    when(kv.txn()).thenReturn(txn);
    CompletableFuture<TxnResponse> future = interruptedFuture();
    when(txn.If(any()).Then(any()).commit()).thenReturn(future);
    doReturn(CompletableFuture.completedFuture(null)).when(lease).revoke(anyLong());
    byte[] value = "v".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofSeconds(5);

    assertThatThrownBy(() -> atom.set("substrate:atom:k", value, "tok", ttl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Interrupted while set atom in etcd");
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void currentKvInterruptWrapsAndSetsInterrupt() throws Exception {
    CompletableFuture<GetResponse> future = interruptedFuture();
    when(kv.get(any(ByteSequence.class), any(GetOption.class))).thenReturn(future);
    Duration ttl = Duration.ofSeconds(5);

    assertThatThrownBy(() -> atom.touch("substrate:atom:k", ttl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Interrupted while reading atom from etcd");
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void ttlOfZeroCoercesToOneSecond() {
    stubGrantLease(1L);
    stubTxn(true);

    atom.create("substrate:atom:k", "v".getBytes(StandardCharsets.UTF_8), "tok", Duration.ZERO);

    // grant(1) was called because max(1, 0) == 1
    org.mockito.Mockito.verify(lease).grant(1L);
  }
}
