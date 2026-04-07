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
package org.jwcarman.substrate.journal.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.ringbuffer.Ringbuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jwcarman.substrate.spi.AbstractJournal;
import org.jwcarman.substrate.spi.JournalEntry;
import tools.jackson.databind.ObjectMapper;

public class HazelcastJournal extends AbstractJournal {

  private static final String COMPLETED_MAP_NAME = "substrate-journal-completed";

  private final HazelcastInstance hazelcast;
  private final ObjectMapper objectMapper;
  private final int batchSize;

  public HazelcastJournal(
      HazelcastInstance hazelcast, ObjectMapper objectMapper, String prefix, int batchSize) {
    super(prefix);
    this.hazelcast = hazelcast;
    this.objectMapper = objectMapper;
    this.batchSize = batchSize;
  }

  @Override
  public String append(String key, String data) {
    return append(key, data, null);
  }

  @Override
  public String append(String key, String data, Duration ttl) {
    Ringbuffer<String> ringbuffer = hazelcast.getRingbuffer(key);
    String json = serialize(data);
    long sequence = ringbuffer.add(json);
    return String.valueOf(sequence);
  }

  @Override
  public Stream<JournalEntry> readAfter(String key, String afterId) {
    Ringbuffer<String> ringbuffer = hazelcast.getRingbuffer(key);
    long startSequence = Long.parseLong(afterId) + 1;
    long tailSequence = ringbuffer.tailSequence();

    if (startSequence > tailSequence || tailSequence == -1) {
      return Stream.empty();
    }

    long headSequence = ringbuffer.headSequence();
    long readFrom = Math.max(startSequence, headSequence);
    int count = (int) Math.min(tailSequence - readFrom + 1, batchSize);

    if (count <= 0) {
      return Stream.empty();
    }

    try {
      var resultSet =
          ringbuffer.readManyAsync(readFrom, 0, count, null).toCompletableFuture().join();
      List<JournalEntry> entries = new ArrayList<>();
      for (int i = 0; i < resultSet.readCount(); i++) {
        String json = resultSet.get(i);
        entries.add(deserialize(json, key, String.valueOf(readFrom + i)));
      }
      return entries.stream();
    } catch (Exception e) {
      return Stream.empty();
    }
  }

  @Override
  public Stream<JournalEntry> readLast(String key, int count) {
    Ringbuffer<String> ringbuffer = hazelcast.getRingbuffer(key);
    long tailSequence = ringbuffer.tailSequence();

    if (tailSequence == -1) {
      return Stream.empty();
    }

    long headSequence = ringbuffer.headSequence();
    long startSequence = Math.max(headSequence, tailSequence - count + 1);
    int readCount = (int) (tailSequence - startSequence + 1);

    if (readCount <= 0) {
      return Stream.empty();
    }

    try {
      var resultSet =
          ringbuffer.readManyAsync(startSequence, 0, readCount, null).toCompletableFuture().join();
      List<JournalEntry> entries = new ArrayList<>();
      for (int i = 0; i < resultSet.readCount(); i++) {
        String json = resultSet.get(i);
        entries.add(deserialize(json, key, String.valueOf(startSequence + i)));
      }
      return entries.stream();
    } catch (Exception e) {
      return Stream.empty();
    }
  }

  @Override
  public void complete(String key) {
    IMap<String, Boolean> completedMap = hazelcast.getMap(COMPLETED_MAP_NAME);
    completedMap.put(key, Boolean.TRUE);
  }

  @Override
  public void delete(String key) {
    Ringbuffer<String> ringbuffer = hazelcast.getRingbuffer(key);
    ringbuffer.destroy();
    IMap<String, Boolean> completedMap = hazelcast.getMap(COMPLETED_MAP_NAME);
    completedMap.remove(key);
  }

  private String serialize(String data) {
    try {
      StoredEntry entry = new StoredEntry(data, Instant.now().toString());
      return objectMapper.writeValueAsString(entry);
    } catch (tools.jackson.core.JacksonException e) {
      throw new IllegalStateException("Failed to serialize journal entry", e);
    }
  }

  private JournalEntry deserialize(String json, String key, String id) {
    try {
      StoredEntry stored = objectMapper.readValue(json, StoredEntry.class);
      return new JournalEntry(id, key, stored.data(), Instant.parse(stored.timestamp()));
    } catch (tools.jackson.core.JacksonException e) {
      throw new IllegalStateException("Failed to deserialize journal entry", e);
    }
  }

  record StoredEntry(String data, String timestamp) {}
}
