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
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.spi.JournalSpi;
import org.jwcarman.substrate.spi.NotificationHandler;
import org.jwcarman.substrate.spi.Notifier;
import org.jwcarman.substrate.spi.RawJournalEntry;

class DefaultJournalCursor<T> implements JournalCursor<T> {

  private final JournalSpi journalSpi;
  private final String key;
  private final Codec<T> codec;
  private final Semaphore semaphore = new Semaphore(0);
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final NotificationHandler handler;

  private List<RawJournalEntry> buffer = List.of();
  private int bufferIndex;
  private String lastId;

  DefaultJournalCursor(
      JournalSpi journalSpi, String key, Codec<T> codec, Notifier notifier, String afterId) {
    this.journalSpi = journalSpi;
    this.key = key;
    this.codec = codec;
    this.lastId = afterId;

    this.handler =
        (notifiedKey, payload) -> {
          if (key.equals(notifiedKey)) {
            semaphore.release();
          }
        };
    notifier.subscribe(handler);
  }

  @Override
  public Optional<JournalEntry<T>> poll(Duration timeout) {
    if (!open.get()) {
      return Optional.empty();
    }

    // Step 1: check buffer
    if (bufferIndex < buffer.size()) {
      return Optional.of(consumeNext());
    }

    // Step 2: fetch from SPI
    if (fetchFromSpi()) {
      return Optional.of(consumeNext());
    }

    // Step 3: check completion
    if (journalSpi.isComplete(key)) {
      open.set(false);
      return Optional.empty();
    }

    // Step 4: wait for nudge, then retry
    try {
      if (semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        // Drain extra permits — we only need one wake-up
        semaphore.drainPermits();

        if (!open.get()) {
          return Optional.empty();
        }

        if (fetchFromSpi()) {
          return Optional.of(consumeNext());
        }

        if (journalSpi.isComplete(key)) {
          open.set(false);
        }
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      open.set(false);
    }

    return Optional.empty();
  }

  @Override
  public boolean isOpen() {
    return open.get();
  }

  @Override
  public String lastId() {
    return lastId;
  }

  @Override
  public void close() {
    open.set(false);
    semaphore.release();
  }

  void preload(List<RawJournalEntry> entries) {
    if (!entries.isEmpty()) {
      buffer = entries;
      bufferIndex = 0;
    }
  }

  private boolean fetchFromSpi() {
    List<RawJournalEntry> entries;
    if (lastId != null) {
      entries = journalSpi.readAfter(key, lastId);
    } else {
      entries = journalSpi.readLast(key, Integer.MAX_VALUE);
    }
    if (!entries.isEmpty()) {
      buffer = entries;
      bufferIndex = 0;
      return true;
    }
    return false;
  }

  private JournalEntry<T> consumeNext() {
    RawJournalEntry raw = buffer.get(bufferIndex++);
    lastId = raw.id();
    return new JournalEntry<>(raw.id(), raw.key(), codec.decode(raw.data()), raw.timestamp());
  }
}
