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
package org.jwcarman.substrate.core.journal;

import java.time.Duration;
import java.util.List;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.journal.Journal;
import org.jwcarman.substrate.journal.JournalCursor;

public class DefaultJournal<T> implements Journal<T> {

  private final JournalSpi journalSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;
  private final Duration maxEntryTtl;
  private final Duration maxRetentionTtl;

  public DefaultJournal(
      JournalSpi journalSpi,
      String key,
      Codec<T> codec,
      NotifierSpi notifier,
      Duration maxEntryTtl,
      Duration maxRetentionTtl) {
    this.journalSpi = journalSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
    this.maxEntryTtl = maxEntryTtl;
    this.maxRetentionTtl = maxRetentionTtl;
  }

  @Override
  public String append(T data, Duration ttl) {
    if (ttl.compareTo(maxEntryTtl) > 0) {
      throw new IllegalArgumentException(
          "Journal entry TTL " + ttl + " exceeds configured maximum " + maxEntryTtl);
    }
    byte[] bytes = codec.encode(data);
    String entryId = journalSpi.append(key, bytes, ttl);
    notifier.notify(key, entryId);
    return entryId;
  }

  @Override
  public void complete(Duration retentionTtl) {
    if (retentionTtl.compareTo(maxRetentionTtl) > 0) {
      throw new IllegalArgumentException(
          "Journal retention TTL "
              + retentionTtl
              + " exceeds configured maximum "
              + maxRetentionTtl);
    }
    journalSpi.complete(key, retentionTtl);
    notifier.notify(key, "__COMPLETED__");
  }

  @Override
  public void delete() {
    journalSpi.delete(key);
  }

  @Override
  public JournalCursor<T> read() {
    List<RawJournalEntry> lastEntries = journalSpi.readLast(key, 1);
    String tailId = lastEntries.isEmpty() ? null : lastEntries.getLast().id();
    return new DefaultJournalCursor<>(journalSpi, key, codec, notifier, tailId);
  }

  @Override
  public JournalCursor<T> readAfter(String afterId) {
    return new DefaultJournalCursor<>(journalSpi, key, codec, notifier, afterId);
  }

  @Override
  public JournalCursor<T> readLast(int count) {
    List<RawJournalEntry> entries = journalSpi.readLast(key, count);
    return new DefaultJournalCursor<>(journalSpi, key, codec, notifier, null, entries);
  }

  @Override
  public String key() {
    return key;
  }
}
