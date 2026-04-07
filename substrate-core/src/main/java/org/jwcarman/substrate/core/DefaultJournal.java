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
package org.jwcarman.substrate.core;

import java.time.Duration;
import java.util.List;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.spi.JournalSpi;
import org.jwcarman.substrate.spi.Notifier;
import org.jwcarman.substrate.spi.RawJournalEntry;

public class DefaultJournal<T> implements Journal<T> {

  private final JournalSpi journalSpi;
  private final String key;
  private final Codec<T> codec;
  private final Notifier notifier;

  public DefaultJournal(JournalSpi journalSpi, String key, Codec<T> codec, Notifier notifier) {
    this.journalSpi = journalSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
  }

  @Override
  public String append(T data) {
    byte[] bytes = codec.encode(data);
    String entryId = journalSpi.append(key, bytes);
    notifier.notify(key, entryId);
    return entryId;
  }

  @Override
  public String append(T data, Duration ttl) {
    byte[] bytes = codec.encode(data);
    String entryId = journalSpi.append(key, bytes, ttl);
    notifier.notify(key, entryId);
    return entryId;
  }

  @Override
  public void complete() {
    journalSpi.complete(key);
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
