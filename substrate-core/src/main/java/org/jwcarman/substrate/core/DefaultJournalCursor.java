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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.spi.JournalSpi;
import org.jwcarman.substrate.spi.Notifier;
import org.jwcarman.substrate.spi.RawJournalEntry;

class DefaultJournalCursor<T> implements JournalCursor<T> {

  private static final Duration READER_POLL_TIMEOUT = Duration.ofSeconds(1);
  private static final int QUEUE_CAPACITY = 1024;

  private sealed interface QueueItem<T> {
    record Entry<T>(JournalEntry<T> entry) implements QueueItem<T> {}

    record Complete<T>() implements QueueItem<T> {}
  }

  private final AtomicBoolean open = new AtomicBoolean(true);
  private final LinkedBlockingQueue<QueueItem<T>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
  private final Semaphore semaphore = new Semaphore(0);

  private volatile String lastId;

  DefaultJournalCursor(
      JournalSpi journalSpi,
      String key,
      Codec<T> codec,
      Notifier notifier,
      String afterId,
      List<RawJournalEntry> preloadedEntries) {
    this.lastId = afterId;

    // Seed the queue with preloaded entries and advance the reader's starting position
    String readStart = afterId;
    for (RawJournalEntry raw : preloadedEntries) {
      queue.add(
          new QueueItem.Entry<>(
              new JournalEntry<>(raw.id(), raw.key(), codec.decode(raw.data()), raw.timestamp())));
      readStart = raw.id();
    }

    final String readerStartId = readStart;

    notifier.subscribe(
        (notifiedKey, payload) -> {
          if (key.equals(notifiedKey)) {
            semaphore.release();
          }
        });

    Thread.ofVirtual()
        .start(
            () -> {
              String readerId = readerStartId;
              try {
                while (open.get()) {
                  List<RawJournalEntry> entries;
                  if (readerId != null) {
                    entries = journalSpi.readAfter(key, readerId);
                  } else {
                    entries = journalSpi.readLast(key, Integer.MAX_VALUE);
                  }

                  for (RawJournalEntry raw : entries) {
                    JournalEntry<T> decoded =
                        new JournalEntry<>(
                            raw.id(), raw.key(), codec.decode(raw.data()), raw.timestamp());
                    queue.put(new QueueItem.Entry<>(decoded));
                    readerId = raw.id();
                  }

                  if (journalSpi.isComplete(key) && entries.isEmpty()) {
                    queue.put(new QueueItem.Complete<>());
                    return;
                  }

                  semaphore.tryAcquire(READER_POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                  semaphore.drainPermits();
                }
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            });
  }

  DefaultJournalCursor(
      JournalSpi journalSpi, String key, Codec<T> codec, Notifier notifier, String afterId) {
    this(journalSpi, key, codec, notifier, afterId, List.of());
  }

  @Override
  public Optional<JournalEntry<T>> poll(Duration timeout) {
    if (!open.get()) {
      return Optional.empty();
    }

    try {
      QueueItem<T> item = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (item == null) {
        return Optional.empty();
      }
      return switch (item) {
        case QueueItem.Entry<T> entry -> {
          lastId = entry.entry().id();
          yield Optional.of(entry.entry());
        }
        case QueueItem.Complete<T> _ -> {
          open.set(false);
          yield Optional.empty();
        }
      };
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      open.set(false);
      return Optional.empty();
    }
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
    // Wake the reader thread so it exits its loop
    semaphore.release();
    // Wake the consumer if it's blocked on queue.poll()
    queue.offer(new QueueItem.Complete<>());
  }
}
