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
package org.jwcarman.substrate.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.jwcarman.substrate.spi.AbstractJournalSpi;
import org.jwcarman.substrate.spi.JournalEntry;

public class InMemoryJournalSpi extends AbstractJournalSpi {

  private static final int DEFAULT_MAX_LEN = 100_000;

  private final ConcurrentMap<String, BoundedEntryList> journals = new ConcurrentHashMap<>();
  private final Set<String> completed = ConcurrentHashMap.newKeySet();
  private final int maxLen;
  private final AtomicLong counter = new AtomicLong(0);

  public InMemoryJournalSpi() {
    this(DEFAULT_MAX_LEN);
  }

  public InMemoryJournalSpi(int maxLen) {
    super("substrate:journal:");
    this.maxLen = maxLen;
  }

  @Override
  public String append(String key, byte[] data) {
    return append(key, data, null);
  }

  @Override
  public String append(String key, byte[] data, Duration ttl) {
    if (completed.contains(key)) {
      throw new IllegalStateException("Journal '" + key + "' is completed");
    }
    String entryId = counter.incrementAndGet() + "-0";
    JournalEntry entry = new JournalEntry(entryId, key, data, Instant.now());
    journals.computeIfAbsent(key, k -> new BoundedEntryList(maxLen)).add(entry);
    return entryId;
  }

  @Override
  public Stream<JournalEntry> readAfter(String key, String afterId) {
    BoundedEntryList list = journals.get(key);
    if (list == null) {
      return Stream.empty();
    }
    long cursor = parseId(afterId);
    return list.snapshot().stream().filter(e -> parseId(e.id()) > cursor);
  }

  @Override
  public Stream<JournalEntry> readLast(String key, int count) {
    BoundedEntryList list = journals.get(key);
    if (list == null) {
      return Stream.empty();
    }
    List<JournalEntry> snapshot = list.snapshot();
    int start = Math.max(0, snapshot.size() - count);
    return snapshot.subList(start, snapshot.size()).stream();
  }

  @Override
  public void complete(String key) {
    completed.add(key);
  }

  @Override
  public boolean isCompleted(String key) {
    return completed.contains(key);
  }

  @Override
  public void delete(String key) {
    journals.remove(key);
    completed.remove(key);
  }

  private static long parseId(String id) {
    int dash = id.indexOf('-');
    return Long.parseLong(dash >= 0 ? id.substring(0, dash) : id);
  }

  private static final class BoundedEntryList {

    private final int maxSize;
    private final LinkedList<JournalEntry> entries = new LinkedList<>();

    BoundedEntryList(int maxSize) {
      this.maxSize = maxSize;
    }

    synchronized void add(JournalEntry entry) {
      entries.addLast(entry);
      while (entries.size() > maxSize) {
        entries.removeFirst();
      }
    }

    synchronized List<JournalEntry> snapshot() {
      return new ArrayList<>(entries);
    }
  }
}
