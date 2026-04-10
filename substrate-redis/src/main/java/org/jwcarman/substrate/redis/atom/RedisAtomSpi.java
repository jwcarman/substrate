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
package org.jwcarman.substrate.redis.atom;

import io.lettuce.core.ExpireArgs;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.RawAtom;

public class RedisAtomSpi extends AbstractAtomSpi {

  private final RedisCommands<String, String> commands;

  public RedisAtomSpi(RedisCommands<String, String> commands, String prefix) {
    super(prefix);
    this.commands = commands;
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    String payload = AtomPayload.encode(value, token);
    String result = commands.set(key, payload, SetArgs.Builder.nx().ex(ttl.toSeconds()));
    if (result == null) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    String payload = commands.get(key);
    if (payload == null) {
      return Optional.empty();
    }
    return Optional.of(AtomPayload.decode(payload));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    String payload = AtomPayload.encode(value, token);
    String result = commands.set(key, payload, SetArgs.Builder.xx().ex(ttl.toSeconds()));
    return result != null;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    return commands.expire(key, ttl.toSeconds(), ExpireArgs.Builder.xx());
  }

  @Override
  public void delete(String key) {
    commands.del(key);
  }
}
