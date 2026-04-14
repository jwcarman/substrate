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
    long leaseId = grantLease(ttl);
    try {
      var txnResponse =
          client
              .getKVClient()
              .txn()
              .If(new Cmp(keyBs, Cmp.Op.EQUAL, CmpTarget.createRevision(0L)))
              .Then(Op.put(keyBs, bs(AtomPayload.encode(value, token)), putWithLease(leaseId)))
              .commit()
              .get();
      if (!txnResponse.isSucceeded()) {
        revokeQuiet(leaseId);
        throw new AtomAlreadyExistsException(key);
      }
    } catch (InterruptedException e) {
      revokeQuiet(leaseId);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while creating atom in etcd", e);
    } catch (ExecutionException e) {
      revokeQuiet(leaseId);
      throw new IllegalStateException("Failed to create atom in etcd", e.getCause());
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
    long newLeaseId = grantLease(ttl);
    try {
      var txnResponse =
          client
              .getKVClient()
              .txn()
              .If(new Cmp(keyBs, Cmp.Op.GREATER, CmpTarget.createRevision(0L)))
              .Then(Op.put(keyBs, bs(AtomPayload.encode(value, token)), putWithLease(newLeaseId)))
              .commit()
              .get();
      if (!txnResponse.isSucceeded()) {
        revokeQuiet(newLeaseId);
        return false;
      }
      if (oldLeaseId > 0) {
        revokeQuiet(oldLeaseId);
      }
      return true;
    } catch (InterruptedException e) {
      revokeQuiet(newLeaseId);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while setting atom in etcd", e);
    } catch (ExecutionException e) {
      revokeQuiet(newLeaseId);
      throw new IllegalStateException("Failed to set atom in etcd", e.getCause());
    }
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    ByteSequence keyBs = bs(key);
    KeyValue current = currentKv(keyBs);
    if (current == null) {
      return false;
    }
    long newLeaseId = grantLease(ttl);
    try {
      var txnResponse =
          client
              .getKVClient()
              .txn()
              .If(new Cmp(keyBs, Cmp.Op.GREATER, CmpTarget.createRevision(0L)))
              .Then(Op.put(keyBs, current.getValue(), putWithLease(newLeaseId)))
              .commit()
              .get();
      if (!txnResponse.isSucceeded()) {
        revokeQuiet(newLeaseId);
        return false;
      }
      if (current.getLease() > 0) {
        revokeQuiet(current.getLease());
      }
      return true;
    } catch (InterruptedException e) {
      revokeQuiet(newLeaseId);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while touching atom in etcd", e);
    } catch (ExecutionException e) {
      revokeQuiet(newLeaseId);
      throw new IllegalStateException("Failed to touch atom in etcd", e.getCause());
    }
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

  private static PutOption putWithLease(long leaseId) {
    return PutOption.builder().withLeaseId(leaseId).build();
  }
}
