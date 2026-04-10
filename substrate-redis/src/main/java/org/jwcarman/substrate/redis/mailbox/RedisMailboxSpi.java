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
package org.jwcarman.substrate.redis.mailbox;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.jwcarman.substrate.core.mailbox.AbstractMailboxSpi;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;

public class RedisMailboxSpi extends AbstractMailboxSpi {

  private static final String CREATED_MARKER = "";

  private final RedisCommands<String, String> commands;

  public RedisMailboxSpi(RedisCommands<String, String> commands, String prefix) {
    super(prefix);
    this.commands = commands;
  }

  @Override
  public void create(String key, Duration ttl) {
    commands.set(key, CREATED_MARKER, SetArgs.Builder.ex(ttl.toSeconds()));
  }

  @Override
  public void deliver(String key, byte[] value) {
    String result =
        commands.set(
            key, Base64.getEncoder().encodeToString(value), SetArgs.Builder.keepttl().xx());
    if (result == null) {
      throw new MailboxExpiredException(key);
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    String encoded = commands.get(key);
    if (encoded == null) {
      throw new MailboxExpiredException(key);
    }
    if (encoded.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Base64.getDecoder().decode(encoded));
  }

  @Override
  public void delete(String key) {
    commands.del(key);
  }
}
