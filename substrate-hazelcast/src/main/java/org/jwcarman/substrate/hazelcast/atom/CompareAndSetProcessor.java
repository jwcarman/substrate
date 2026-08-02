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
package org.jwcarman.substrate.hazelcast.atom;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.core.atom.CasResult;

/**
 * Compares an atom's token and, on a match, writes the new value and TTL — all as a single
 * partition-local operation, so no concurrent writer can interleave.
 */
public class CompareAndSetProcessor
    implements EntryProcessor<String, AtomEntry, CasResult>, Serializable {

  private static final long serialVersionUID = 1L;

  private final String expectedToken;
  private final byte[] value;
  private final String newToken;
  private final long ttlMillis;

  /**
   * Creates a processor that conditionally overwrites an existing atom.
   *
   * @param expectedToken the token the stored atom must currently carry
   * @param value the new value
   * @param newToken the new token
   * @param ttlMillis the time-to-live to apply on commit, in milliseconds
   */
  public CompareAndSetProcessor(
      String expectedToken, byte[] value, String newToken, long ttlMillis) {
    this.expectedToken = expectedToken;
    this.value = value;
    this.newToken = newToken;
    this.ttlMillis = ttlMillis;
  }

  @Override
  public CasResult process(Map.Entry<String, AtomEntry> entry) {
    AtomEntry current = entry.getValue();
    if (current == null) {
      return CasResult.ABSENT;
    }
    if (!current.token().equals(expectedToken)) {
      return CasResult.TOKEN_MISMATCH;
    }
    if (entry instanceof ExtendedMapEntry<String, AtomEntry> extended) {
      extended.setValue(new AtomEntry(value, newToken), ttlMillis, TimeUnit.MILLISECONDS);
      return CasResult.COMMITTED;
    }
    throw new IllegalStateException(
        "Hazelcast entry does not support per-entry TTL: " + entry.getClass());
  }
}
