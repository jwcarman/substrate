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
package org.jwcarman.substrate.hazelcast.mailbox;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.core.mailbox.AbstractMailboxSpi;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

public class HazelcastMailboxSpi extends AbstractMailboxSpi {

  private static final byte[] CREATED_MARKER = new byte[0];

  private final IMap<String, byte[]> map;

  public HazelcastMailboxSpi(HazelcastInstance hazelcastInstance, String prefix, String mapName) {
    super(prefix);
    this.map = hazelcastInstance.getMap(mapName);
  }

  @Override
  public void create(String key, Duration ttl) {
    map.put(key, CREATED_MARKER, ttl.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void deliver(String key, byte[] value) {
    if (!map.replace(key, CREATED_MARKER, value)) {
      if (map.containsKey(key)) {
        throw new MailboxFullException(key);
      }
      throw new MailboxExpiredException(key);
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    byte[] value = map.get(key);
    if (value == null) {
      throw new MailboxExpiredException(key);
    }
    if (value.length == 0) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  @Override
  public void delete(String key) {
    map.remove(key);
  }

  @Override
  public boolean exists(String key) {
    return map.containsKey(key);
  }
}
