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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.spi.JournalEntry;
import org.jwcarman.substrate.spi.JournalSpi;
import org.jwcarman.substrate.spi.Notifier;

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
  public Stream<TypedJournalEntry<T>> readAfter(String afterId) {
    return journalSpi.readAfter(key, afterId).map(this::toTyped);
  }

  @Override
  public Stream<TypedJournalEntry<T>> readLast(int count) {
    return journalSpi.readLast(key, count).map(this::toTyped);
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
  public String key() {
    return key;
  }

  @Override
  public Subscription subscribe(JournalSubscriber<T> subscriber) {
    // Snapshot the current tail so we only receive new entries
    List<JournalEntry> lastEntries = journalSpi.readLast(key, 1).toList();
    String tailId = lastEntries.isEmpty() ? null : lastEntries.getLast().id();
    return startSubscription(tailId, subscriber);
  }

  @Override
  public Subscription subscribe(String afterId, JournalSubscriber<T> subscriber) {
    return startSubscription(afterId, subscriber);
  }

  private Subscription startSubscription(String initialCursor, JournalSubscriber<T> subscriber) {
    Object monitor = new Object();
    AtomicBoolean woken = new AtomicBoolean(false);

    // Register notifier BEFORE starting the reader to avoid missing signals
    notifier.subscribe(
        (notifiedKey, payload) -> {
          if (key.equals(notifiedKey)) {
            synchronized (monitor) {
              woken.set(true);
              monitor.notifyAll();
            }
          }
        });

    Thread readerThread =
        Thread.ofVirtual()
            .name("journal-subscriber-" + key)
            .start(() -> runReaderLoop(initialCursor, subscriber, monitor, woken));

    return () -> readerThread.interrupt();
  }

  private void runReaderLoop(
      String initialCursor, JournalSubscriber<T> subscriber, Object monitor, AtomicBoolean woken) {
    String cursor = initialCursor;
    try {
      while (!Thread.currentThread().isInterrupted()) {
        List<JournalEntry> entries;
        if (cursor != null) {
          entries = journalSpi.readAfter(key, cursor).toList();
        } else {
          entries = journalSpi.readLast(key, Integer.MAX_VALUE).toList();
        }

        for (JournalEntry entry : entries) {
          subscriber.onEntry(toTyped(entry));
          cursor = entry.id();
        }

        if (journalSpi.isCompleted(key)) {
          subscriber.onComplete();
          return;
        }

        synchronized (monitor) {
          while (!woken.compareAndSet(true, false)) {
            monitor.wait();
          }
        }
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private TypedJournalEntry<T> toTyped(JournalEntry entry) {
    return new TypedJournalEntry<>(
        entry.id(), entry.key(), codec.decode(entry.data()), entry.timestamp());
  }
}
