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
package org.jwcarman.substrate.core.memory.journal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jwcarman.substrate.core.journal.AbstractJournalSpi;
import org.jwcarman.substrate.core.journal.RawJournalEntry;

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
  public String append(String key, byte[] data, Duration ttl) {
    if (completed.contains(key)) {
      throw new IllegalStateException("Journal '" + key + "' is completed");
    }
    String entryId = counter.incrementAndGet() + "-0";
    Instant expiresAt = Instant.now().plus(ttl);
    RawJournalEntry entry = new RawJournalEntry(entryId, key, data, Instant.now());
    journals.computeIfAbsent(key, k -> new BoundedEntryList(maxLen)).add(entry, expiresAt);
    return entryId;
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    BoundedEntryList list = journals.get(key);
    if (list == null) {
      return List.of();
    }
    long cursor = parseId(afterId);
    Instant now = Instant.now();
    return list.snapshot().stream()
        .filter(e -> parseId(e.entry().id()) > cursor && e.expiresAt().isAfter(now))
        .map(TimedEntry::entry)
        .toList();
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    BoundedEntryList list = journals.get(key);
    if (list == null) {
      return List.of();
    }
    Instant now = Instant.now();
    List<RawJournalEntry> live =
        list.snapshot().stream()
            .filter(e -> e.expiresAt().isAfter(now))
            .map(TimedEntry::entry)
            .toList();
    int start = Math.max(0, live.size() - count);
    return List.copyOf(live.subList(start, live.size()));
  }

  @Override
  public void complete(String key) {
    completed.add(key);
  }

  @Override
  public boolean isComplete(String key) {
    return completed.contains(key);
  }

  @Override
  public int sweep(int maxToSweep) {
    Instant now = Instant.now();
    int removed = 0;
    for (BoundedEntryList list : journals.values()) {
      if (removed >= maxToSweep) {
        break;
      }
      removed += list.removeExpired(now, maxToSweep - removed);
    }
    return removed;
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

  private record TimedEntry(RawJournalEntry entry, Instant expiresAt) {}

  private static final class BoundedEntryList {

    private final int maxSize;
    private final LinkedList<TimedEntry> entries = new LinkedList<>();

    BoundedEntryList(int maxSize) {
      this.maxSize = maxSize;
    }

    synchronized void add(RawJournalEntry entry, Instant expiresAt) {
      // Lazy sweep: remove expired entries
      Instant now = Instant.now();
      entries.removeIf(e -> e.expiresAt().isBefore(now));

      entries.addLast(new TimedEntry(entry, expiresAt));
      while (entries.size() > maxSize) {
        entries.removeFirst();
      }
    }

    synchronized int removeExpired(Instant now, int max) {
      int removed = 0;
      var iterator = entries.iterator();
      while (iterator.hasNext() && removed < max) {
        if (iterator.next().expiresAt().isBefore(now)) {
          iterator.remove();
          removed++;
        }
      }
      return removed;
    }

    synchronized List<TimedEntry> snapshot() {
      return new ArrayList<>(entries);
    }
  }
}
