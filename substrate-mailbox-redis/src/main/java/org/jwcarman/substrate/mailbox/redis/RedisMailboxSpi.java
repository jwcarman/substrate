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
package org.jwcarman.substrate.mailbox.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.jwcarman.substrate.spi.AbstractMailboxSpi;

public class RedisMailboxSpi extends AbstractMailboxSpi {

  private final RedisCommands<String, String> commands;
  private final Duration defaultTtl;

  public RedisMailboxSpi(
      RedisCommands<String, String> commands, String prefix, Duration defaultTtl) {
    super(prefix);
    this.commands = commands;
    this.defaultTtl = defaultTtl;
  }

  @Override
  public void deliver(String key, byte[] value) {
    SetArgs setArgs = SetArgs.Builder.ex(defaultTtl.toSeconds());
    commands.set(key, Base64.getEncoder().encodeToString(value), setArgs);
  }

  @Override
  public Optional<byte[]> get(String key) {
    String encoded = commands.get(key);
    if (encoded != null) {
      return Optional.of(Base64.getDecoder().decode(encoded));
    }
    return Optional.empty();
  }

  @Override
  public void delete(String key) {
    commands.del(key);
  }
}
