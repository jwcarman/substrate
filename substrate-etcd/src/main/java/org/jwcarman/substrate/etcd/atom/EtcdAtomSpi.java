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

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.LongFunction;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.RawAtom;

/**
 * Atom storage backed by etcd. Each atom is a single key whose value carries the substrate
 * staleness token and payload concatenated. TTL is enforced via an etcd lease: every write attaches
 * the key to a freshly granted lease, so the key auto-deletes when the lease expires. {@link
 * #touch(String, Duration)} rotates in a new lease of the requested TTL, preserving the current
 * value.
 *
 * <p>Old leases are revoked after a successful replacement write; on transaction failure the
 * freshly granted lease is revoked to avoid orphans. In the (rare) interleaved-writer case an old
 * lease may be left to expire naturally instead of being revoked — the upper bound on orphaned
 * leases is therefore the TTL window.
 */
public class EtcdAtomSpi extends AbstractAtomSpi {

  private final Client client;

  public EtcdAtomSpi(Client client, String prefix) {
    super(prefix);
    this.client = client;
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    ByteSequence keyBs = bs(key);
    boolean created =
        leasedWrite(
            new Cmp(keyBs, Cmp.Op.EQUAL, CmpTarget.createRevision(0L)),
            newLeaseId ->
                Op.put(keyBs, bs(AtomPayload.encode(value, token)), withLease(newLeaseId)),
            0L,
            ttl,
            "create atom in etcd");
    if (!created) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    try {
      var response = client.getKVClient().get(bs(key)).get();
      List<KeyValue> kvs = response.getKvs();
      if (kvs.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(AtomPayload.decode(kvs.get(0).getValue().getBytes()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading atom from etcd", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to read atom from etcd", e.getCause());
    }
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    ByteSequence keyBs = bs(key);
    long oldLeaseId = currentLeaseId(keyBs);
    if (oldLeaseId == -1L) {
      return false;
    }
    return leasedWrite(
        new Cmp(keyBs, Cmp.Op.GREATER, CmpTarget.createRevision(0L)),
        newLeaseId -> Op.put(keyBs, bs(AtomPayload.encode(value, token)), withLease(newLeaseId)),
        oldLeaseId,
        ttl,
        "set atom in etcd");
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    ByteSequence keyBs = bs(key);
    KeyValue current = currentKv(keyBs);
    if (current == null) {
      return false;
    }
    // CAS on the modRevision we observed so a concurrent set() can't cause touch to
    // re-put a stale value on top of a newer one. If the CAS fails the key is still
    // alive (a concurrent writer just refreshed it with their own TTL), so return true.
    boolean applied =
        leasedWrite(
            new Cmp(keyBs, Cmp.Op.EQUAL, CmpTarget.modRevision(current.getModRevision())),
            newLeaseId -> Op.put(keyBs, current.getValue(), withLease(newLeaseId)),
            current.getLease(),
            ttl,
            "touch atom in etcd");
    return applied || atomExists(keyBs);
  }

  /**
   * Runs a put-on-a-fresh-lease txn and cleans up leases on either outcome:
   *
   * <ul>
   *   <li>granted lease is revoked if the txn fails or the commit throws
   *   <li>old lease is revoked after a successful replacement
   * </ul>
   *
   * Returns whether the txn compare succeeded.
   */
  private boolean leasedWrite(
      Cmp condition, LongFunction<Op> putOp, long oldLeaseId, Duration ttl, String description) {
    long newLeaseId = grantLease(ttl);
    try {
      var txnResponse =
          client.getKVClient().txn().If(condition).Then(putOp.apply(newLeaseId)).commit().get();
      if (!txnResponse.isSucceeded()) {
        revokeQuiet(newLeaseId);
        return false;
      }
      revokeQuiet(oldLeaseId);
      return true;
    } catch (InterruptedException e) {
      revokeQuiet(newLeaseId);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while " + description, e);
    } catch (ExecutionException e) {
      revokeQuiet(newLeaseId);
      throw new IllegalStateException("Failed to " + description, e.getCause());
    }
  }

  @Override
  public boolean exists(String key) {
    return atomExists(bs(key));
  }

  @Override
  public void delete(String key) {
    try {
      client.getKVClient().delete(bs(key)).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while deleting atom from etcd", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to delete atom from etcd", e.getCause());
    }
  }

  private long grantLease(Duration ttl) {
    long seconds = Math.max(1, ttl.toSeconds());
    try {
      return client.getLeaseClient().grant(seconds).get().getID();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while granting etcd lease", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to grant etcd lease", e.getCause());
    }
  }

  private void revokeQuiet(long leaseId) {
    if (leaseId <= 0) {
      return;
    }
    client.getLeaseClient().revoke(leaseId);
  }

  private long currentLeaseId(ByteSequence keyBs) {
    KeyValue kv = currentKv(keyBs);
    return kv == null ? -1L : kv.getLease();
  }

  private boolean atomExists(ByteSequence keyBs) {
    return currentKv(keyBs) != null;
  }

  private KeyValue currentKv(ByteSequence keyBs) {
    try {
      var response = client.getKVClient().get(keyBs, GetOption.DEFAULT).get();
      List<KeyValue> kvs = response.getKvs();
      return kvs.isEmpty() ? null : kvs.get(0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading atom from etcd", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to read atom from etcd", e.getCause());
    }
  }

  private static ByteSequence bs(String s) {
    return ByteSequence.from(s, StandardCharsets.UTF_8);
  }

  private static ByteSequence bs(byte[] bytes) {
    return ByteSequence.from(bytes);
  }

  private static PutOption withLease(long leaseId) {
    return PutOption.builder().withLeaseId(leaseId).build();
  }
}
