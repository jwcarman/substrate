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

import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class StreamJournalSubscriber<T> implements JournalSubscriber<T>, AutoCloseable {

  private static final int DEFAULT_CAPACITY = 1024;

  private final BlockingQueue<JournalEntry<T>> queue;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  public StreamJournalSubscriber() {
    this(DEFAULT_CAPACITY);
  }

  public StreamJournalSubscriber(int capacity) {
    this.queue = new LinkedBlockingQueue<>(capacity);
  }

  @Override
  public void onEntry(JournalEntry<T> entry) {
    if (closed.get()) {
      return;
    }
    try {
      queue.put(entry);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void onComplete() {
    closed.set(true);
  }

  public Stream<JournalEntry<T>> stream() {
    Spliterator<JournalEntry<T>> spliterator =
        new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
          @Override
          public boolean tryAdvance(Consumer<? super JournalEntry<T>> action) {
            try {
              while (!closed.get() || !queue.isEmpty()) {
                JournalEntry<T> entry = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (entry != null) {
                  action.accept(entry);
                  return true;
                }
              }
              return false;
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
            }
          }
        };
    return StreamSupport.stream(spliterator, false);
  }

  @Override
  public void close() {
    closed.set(true);
  }
}
