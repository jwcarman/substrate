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
package org.jwcarman.substrate.mailbox.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.spi.AbstractMailboxSpi;

public class HazelcastMailboxSpi extends AbstractMailboxSpi {

  private final IMap<String, byte[]> map;
  private final Duration defaultTtl;

  public HazelcastMailboxSpi(
      HazelcastInstance hazelcastInstance, String prefix, String mapName, Duration defaultTtl) {
    super(prefix);
    this.map = hazelcastInstance.getMap(mapName);
    this.defaultTtl = defaultTtl;
  }

  @Override
  public void deliver(String key, byte[] value) {
    map.put(key, value, defaultTtl.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public Optional<byte[]> get(String key) {
    return Optional.ofNullable(map.get(key));
  }

  @Override
  public void delete(String key) {
    map.remove(key);
  }
}
