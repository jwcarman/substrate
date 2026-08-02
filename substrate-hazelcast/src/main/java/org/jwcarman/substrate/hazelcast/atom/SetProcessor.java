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

/**
 * Writes a new value and TTL to an existing atom as a single partition-local operation.
 *
 * <p>This exists because {@code IMap.replace(key, value)} discards the entry's per-entry TTL,
 * leaving the atom with no expiration until a follow-up {@code setTtl} repairs it. An interruption
 * in that window produces an atom that never expires and is never swept.
 */
public class SetProcessor implements EntryProcessor<String, AtomEntry, Boolean>, Serializable {

  private static final long serialVersionUID = 1L;

  private final byte[] value;
  private final String token;
  private final long ttlMillis;

  /**
   * Creates a processor that overwrites an existing atom.
   *
   * @param value the new value
   * @param token the new token
   * @param ttlMillis the time-to-live to apply, in milliseconds
   */
  public SetProcessor(byte[] value, String token, long ttlMillis) {
    this.value = value;
    this.token = token;
    this.ttlMillis = ttlMillis;
  }

  @Override
  public Boolean process(Map.Entry<String, AtomEntry> entry) {
    if (entry.getValue() == null) {
      return false;
    }
    if (entry instanceof ExtendedMapEntry<String, AtomEntry> extended) {
      extended.setValue(new AtomEntry(value, token), ttlMillis, TimeUnit.MILLISECONDS);
      return true;
    }
    throw new IllegalStateException(
        "Hazelcast entry does not support per-entry TTL: " + entry.getClass());
  }
}
