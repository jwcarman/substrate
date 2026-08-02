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

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.CasResult;
import org.jwcarman.substrate.core.atom.RawAtom;

/**
 * Atom storage backed by a Hazelcast {@code IMap}.
 *
 * <p><strong>Deployment note:</strong> {@link SetProcessor} and {@link CompareAndSetProcessor} run
 * on cluster members, so this module's classes must be present on every member's classpath. That is
 * automatic for embedded Hazelcast. Deployments that reach a remote cluster through a Hazelcast
 * client must either place the substrate jar on the members or enable user-code deployment.
 */
public class HazelcastAtomSpi extends AbstractAtomSpi {

  private final IMap<String, AtomEntry> map;

  /**
   * Creates an atom SPI backed by the named map on the given Hazelcast instance.
   *
   * @param hazelcastInstance the Hazelcast instance holding the map
   * @param prefix the key prefix applied to atom names
   * @param mapName the name of the backing {@code IMap}
   */
  public HazelcastAtomSpi(HazelcastInstance hazelcastInstance, String prefix, String mapName) {
    super(prefix);
    this.map = hazelcastInstance.getMap(mapName);
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    AtomEntry previous =
        map.putIfAbsent(key, new AtomEntry(value, token), ttl.toMillis(), TimeUnit.MILLISECONDS);
    if (previous != null) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    AtomEntry entry = map.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(new RawAtom(entry.value(), entry.token()));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    return Boolean.TRUE.equals(
        map.executeOnKey(key, new SetProcessor(value, token, ttl.toMillis())));
  }

  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    return map.executeOnKey(
        key, new CompareAndSetProcessor(expectedToken, value, newToken, ttl.toMillis()));
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    return map.setTtl(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void delete(String key) {
    map.delete(key);
  }

  @Override
  public boolean exists(String key) {
    return map.containsKey(key);
  }
}
