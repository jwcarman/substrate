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
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.CasResult;
import org.jwcarman.substrate.core.atom.RawAtom;

/**
 * Atom storage backed by Redis. Each atom is a hash with a {@code token} field and a Base64-encoded
 * {@code value} field, with the key's TTL carrying the lease. Writes go through Lua scripts so the
 * field update and the {@code EXPIRE} are applied atomically — an atom can never exist without an
 * expiration — and so {@code compareAndSet} can compare the token server-side.
 */
public class RedisAtomSpi extends AbstractAtomSpi {

  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_VALUE = "value";

  private static final String CREATE_SCRIPT =
      "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
          + "redis.call('HSET', KEYS[1], 'token', ARGV[1], 'value', ARGV[2]) "
          + "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
          + "return 1";

  private static final String SET_SCRIPT =
      "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
          + "redis.call('HSET', KEYS[1], 'token', ARGV[1], 'value', ARGV[2]) "
          + "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
          + "return 1";

  private static final String CAS_SCRIPT =
      "local current = redis.call('HGET', KEYS[1], 'token') "
          + "if not current then return -1 end "
          + "if current ~= ARGV[1] then return 0 end "
          + "redis.call('HSET', KEYS[1], 'token', ARGV[2], 'value', ARGV[3]) "
          + "redis.call('EXPIRE', KEYS[1], ARGV[4]) "
          + "return 1";

  private final RedisCommands<String, String> commands;

  /**
   * Creates a new Redis-backed atom SPI.
   *
   * @param commands the synchronous Redis command interface
   * @param prefix the key prefix applied to atom names
   */
  public RedisAtomSpi(RedisCommands<String, String> commands, String prefix) {
    super(prefix);
    this.commands = commands;
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    Long result =
        commands.eval(
            CREATE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {key},
            token,
            encode(value),
            seconds(ttl));
    if (result == null || result == 0L) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    Map<String, String> fields = commands.hgetall(key);
    if (fields.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new RawAtom(decode(fields.get(FIELD_VALUE)), fields.get(FIELD_TOKEN)));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    Long result =
        commands.eval(
            SET_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {key},
            token,
            encode(value),
            seconds(ttl));
    return result != null && result == 1L;
  }

  @Override
  public CasResult compareAndSet(
      String key, String expectedToken, byte[] value, String newToken, Duration ttl) {
    Long result =
        commands.eval(
            CAS_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {key},
            expectedToken,
            newToken,
            encode(value),
            seconds(ttl));
    if (result == null) {
      return CasResult.ABSENT;
    }
    return switch (result.intValue()) {
      case 1 -> CasResult.COMMITTED;
      case 0 -> CasResult.TOKEN_MISMATCH;
      default -> CasResult.ABSENT;
    };
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    return commands.expire(key, ttl.toSeconds(), ExpireArgs.Builder.xx());
  }

  @Override
  public void delete(String key) {
    commands.del(key);
  }

  @Override
  public boolean exists(String key) {
    return commands.exists(key) > 0;
  }

  private static String encode(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static byte[] decode(String encoded) {
    return Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
  }

  private static String seconds(Duration ttl) {
    return Long.toString(Math.max(1L, ttl.toSeconds()));
  }
}
