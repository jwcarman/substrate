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
package org.jwcarman.substrate.journal.redis;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.substrate.spi.AbstractJournalSpi;
import org.jwcarman.substrate.spi.RawJournalEntry;

public class RedisJournalSpi extends AbstractJournalSpi {

  private static final int READ_BATCH_SIZE = 100;
  private static final String FIELD_DATA = "data";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String COMPLETED_SUFFIX = ":completed";

  private final RedisCommands<String, String> commands;
  private final long maxLen;
  private final Duration defaultTtl;

  public RedisJournalSpi(
      RedisCommands<String, String> commands, String prefix, long maxLen, Duration defaultTtl) {
    super(prefix);
    this.commands = commands;
    this.maxLen = maxLen;
    this.defaultTtl = defaultTtl;
  }

  @Override
  public String append(String key, byte[] data) {
    return append(key, data, defaultTtl);
  }

  @Override
  public String append(String key, byte[] data, Duration ttl) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put(FIELD_DATA, Base64.getEncoder().encodeToString(data));
    body.put(FIELD_TIMESTAMP, Instant.now().toString());

    XAddArgs args = new XAddArgs().maxlen(maxLen).approximateTrimming();
    String entryId = commands.xadd(key, args, body);

    Duration effectiveTtl = ttl != null ? ttl : defaultTtl;
    if (effectiveTtl != null && !effectiveTtl.isZero()) {
      commands.expire(key, effectiveTtl.toSeconds());
    }

    return entryId;
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    List<StreamMessage<String, String>> messages =
        commands.xread(XReadArgs.Builder.count(READ_BATCH_SIZE), StreamOffset.from(key, afterId));
    if (messages == null || messages.isEmpty()) {
      return List.of();
    }
    return messages.stream().map(msg -> toJournalEntry(key, msg)).toList();
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    List<StreamMessage<String, String>> messages =
        commands.xrevrange(key, Range.unbounded(), Limit.create(0, count));
    return messages.reversed().stream().map(msg -> toJournalEntry(key, msg)).toList();
  }

  @Override
  public void complete(String key) {
    commands.set(key + COMPLETED_SUFFIX, "true");
  }

  @Override
  public boolean isComplete(String key) {
    return commands.get(key + COMPLETED_SUFFIX) != null;
  }

  @Override
  public void delete(String key) {
    commands.del(key, key + COMPLETED_SUFFIX);
  }

  private RawJournalEntry toJournalEntry(String key, StreamMessage<String, String> message) {
    Map<String, String> body = message.getBody();
    String encodedData = body.get(FIELD_DATA);
    byte[] data = encodedData != null ? Base64.getDecoder().decode(encodedData) : new byte[0];
    String timestampStr = body.get(FIELD_TIMESTAMP);
    Instant timestamp = timestampStr != null ? Instant.parse(timestampStr) : Instant.now();
    return new RawJournalEntry(message.getId(), key, data, timestamp);
  }
}
