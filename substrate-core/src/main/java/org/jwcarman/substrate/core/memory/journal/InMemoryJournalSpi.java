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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jwcarman.substrate.core.journal.AbstractJournalSpi;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.jwcarman.substrate.journal.JournalAlreadyExistsException;
import org.jwcarman.substrate.journal.JournalCompletedException;
import org.jwcarman.substrate.journal.JournalExpiredException;

public class InMemoryJournalSpi extends AbstractJournalSpi {

  private static final int DEFAULT_MAX_LEN = 100_000;

  private sealed interface State permits Active, Completed {
    boolean isDead(Instant now);

    BoundedEntryList entries();
  }

  private record Active(Instant lastAppendAt, Duration inactivityTtl, BoundedEntryList entries)
      implements State {
    @Override
    public boolean isDead(Instant now) {
      return now.isAfter(lastAppendAt.plus(inactivityTtl));
    }
  }

  private record Completed(Instant completedAt, Duration retentionTtl, BoundedEntryList entries)
      implements State {
    @Override
    public boolean isDead(Instant now) {
      return now.isAfter(completedAt.plus(retentionTtl));
    }
  }

  private final ConcurrentMap<String, State> store = new ConcurrentHashMap<>();
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
  public void create(String key, Duration inactivityTtl) {
    Instant now = Instant.now();
    State newState = new Active(now, inactivityTtl, new BoundedEntryList(maxLen));
    State existing = store.putIfAbsent(key, newState);
    if (existing != null) {
      if (!existing.isDead(now)) {
        throw new JournalAlreadyExistsException(key);
      }
      // Dead journal — replace it
      store.put(key, newState);
    }
  }

  @Override
  public String append(String key, byte[] data, Duration entryTtl) {
    Instant now = Instant.now();
    String entryId = counter.incrementAndGet() + "-0";
    Instant expiresAt = now.plus(entryTtl);
    RawJournalEntry entry = new RawJournalEntry(entryId, key, data, now);

    store.compute(
        key,
        (k, state) -> {
          if (state == null || state.isDead(now)) {
            throw new JournalExpiredException(k);
          }
          return switch (state) {
            case Active active -> {
              active.entries().add(entry, expiresAt);
              yield new Active(now, active.inactivityTtl(), active.entries());
            }
            case Completed _ -> throw new JournalCompletedException(k);
          };
        });

    return entryId;
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    Instant now = Instant.now();
    State state = store.get(key);
    if (state == null) {
      throw new JournalExpiredException(key);
    }
    if (state.isDead(now)) {
      throw new JournalExpiredException(key);
    }
    long cursor = parseId(afterId);
    return state.entries().snapshot().stream()
        .filter(e -> parseId(e.entry().id()) > cursor && e.expiresAt().isAfter(now))
        .map(TimedEntry::entry)
        .toList();
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    Instant now = Instant.now();
    State state = store.get(key);
    if (state == null) {
      throw new JournalExpiredException(key);
    }
    if (state.isDead(now)) {
      throw new JournalExpiredException(key);
    }
    List<RawJournalEntry> live =
        state.entries().snapshot().stream()
            .filter(e -> e.expiresAt().isAfter(now))
            .map(TimedEntry::entry)
            .toList();
    int start = Math.max(0, live.size() - count);
    return List.copyOf(live.subList(start, live.size()));
  }

  @Override
  public void complete(String key, Duration retentionTtl) {
    Instant now = Instant.now();
    store.compute(
        key,
        (k, state) -> {
          if (state == null || state.isDead(now)) {
            throw new JournalExpiredException(k);
          }
          return switch (state) {
            case Active active -> new Completed(now, retentionTtl, active.entries());
            case Completed completed ->
                new Completed(completed.completedAt(), retentionTtl, completed.entries());
          };
        });
  }

  @Override
  public boolean isComplete(String key) {
    Instant now = Instant.now();
    State state = store.get(key);
    return state instanceof Completed && !state.isDead(now);
  }

  @Override
  public int sweep(int maxToSweep) {
    Instant now = Instant.now();
    int removed = 0;
    var iterator = store.entrySet().iterator();
    while (iterator.hasNext() && removed < maxToSweep) {
      var entry = iterator.next();
      if (entry.getValue().isDead(now)) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }

  private static long parseId(String id) {
    int dash = id.indexOf('-');
    return Long.parseLong(dash >= 0 ? id.substring(0, dash) : id);
  }

  record TimedEntry(RawJournalEntry entry, Instant expiresAt) {}

  static final class BoundedEntryList {

    private final int maxSize;
    private final LinkedList<TimedEntry> entries = new LinkedList<>();

    BoundedEntryList(int maxSize) {
      this.maxSize = maxSize;
    }

    synchronized void add(RawJournalEntry entry, Instant expiresAt) {
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
