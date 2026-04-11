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
package org.jwcarman.substrate.core.subscription;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.substrate.NextResult;

/**
 * FIFO bounded-queue handoff strategy. The producer blocks on {@link #push push} when the queue is
 * full, providing backpressure. Used by {@link org.jwcarman.substrate.journal.Journal Journal}
 * subscriptions, which need ordered delivery of every entry.
 *
 * @param <T> the type of values transferred through the handoff
 */
public class BlockingBoundedHandoff<T> extends AbstractHandoff<T> {

  private final BlockingQueue<NextResult<T>> queue;
  private final AtomicBoolean marked = new AtomicBoolean(false);

  public BlockingBoundedHandoff(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.queue = new LinkedBlockingQueue<>(capacity);
  }

  @Override
  public void push(T item) {
    if (marked.get()) return;
    try {
      queue.put(new NextResult.Value<>(item));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void pushAll(List<T> items) {
    for (T item : items) push(item);
  }

  @Override
  public NextResult<T> pull(Duration timeout) {
    try {
      NextResult<T> r = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return r != null ? r : new NextResult.Timeout<>();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return new NextResult.Timeout<>();
    }
  }

  @Override
  protected void mark(NextResult<T> terminal) {
    if (marked.compareAndSet(false, true)) {
      try {
        queue.put(terminal);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void markCancelled() {
    // Offer Cancelled to the tail of the queue so any blocked poll() wakes up
    // with a terminal result. If the queue is already full, the offer is a
    // best-effort signal — a blocked poll() will still see the existing head
    // value and return from next(), after which the consumer's isActive()
    // check will already be false and the loop will exit on the next turn.
    if (marked.compareAndSet(false, true)) {
      // Best-effort offer: if the queue is full, the comment above explains
      // why that's still safe. Bind to an unnamed variable so the ignored
      // return value is explicit.
      boolean _ = queue.offer(new NextResult.Cancelled<>());
    }
  }
}
