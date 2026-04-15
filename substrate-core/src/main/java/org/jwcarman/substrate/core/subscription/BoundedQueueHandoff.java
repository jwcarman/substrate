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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;

/**
 * FIFO bounded-queue handoff. Values sit in an in-memory queue with a bounded capacity so the
 * feeder blocks on {@link #deliver(Object)} when the consumer falls behind, providing backpressure.
 * The terminal marker lives in a separate sticky field rather than being enqueued alongside values,
 * so terminals never contend with values for queue capacity and cannot be dropped when the queue is
 * full. Pending values always drain before the terminal is observed.
 *
 * <p>Used by Journal subscriptions, which need ordered delivery of every entry.
 *
 * @param <T> the type of values transferred through the handoff
 */
public final class BoundedQueueHandoff<T> extends AbstractHandoff<T> {

  private final int capacity;
  private final Deque<T> queue = new ArrayDeque<>();
  private final Condition notFull = lock.newCondition();

  /**
   * Creates a bounded-queue handoff.
   *
   * @param capacity the maximum number of pending values; must be positive
   */
  public BoundedQueueHandoff(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.capacity = capacity;
  }

  @Override
  public void deliver(T item) {
    lock.lock();
    try {
      while (queue.size() >= capacity && terminal == null) {
        try {
          notFull.await();
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      if (terminal != null) return;
      queue.addLast(item);
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected T takeValue() {
    T v = queue.pollFirst();
    if (v != null) notFull.signalAll();
    return v;
  }

  @Override
  protected void onTerminalSet() {
    // Wake any feeder parked in deliver() so it can observe the terminal and bail out.
    notFull.signalAll();
  }
}
