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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.jwcarman.substrate.NextResult;

/**
 * Single-slot latest-wins handoff strategy. The producer never blocks — each {@link #push push}
 * overwrites the previous unconsumed value. Used by {@link org.jwcarman.substrate.atom.Atom Atom}
 * subscriptions, where only the most recent state matters.
 *
 * @param <T> the type of values transferred through the handoff
 */
public class CoalescingHandoff<T> implements NextHandoff<T> {

  private final Lock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private NextResult<T> slot;
  private boolean terminal;

  @Override
  public void push(T item) {
    lock.lock();
    try {
      if (terminal) return;
      slot = new NextResult.Value<>(item);
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void pushAll(List<T> items) {
    if (items.isEmpty()) return;
    push(items.get(items.size() - 1));
  }

  @Override
  public NextResult<T> pull(Duration timeout) {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + timeout.toNanos();
      while (slot == null) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return new NextResult.Timeout<>();
        try {
          remaining = notEmpty.awaitNanos(remaining);
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          return new NextResult.Timeout<>();
        }
      }
      NextResult<T> result = slot;
      if (result instanceof NextResult.Value<T>) {
        slot = null;
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void error(Throwable cause) {
    mark(new NextResult.Errored<>(cause));
  }

  @Override
  public void markCompleted() {
    mark(new NextResult.Completed<>());
  }

  @Override
  public void markExpired() {
    mark(new NextResult.Expired<>());
  }

  @Override
  public void markDeleted() {
    mark(new NextResult.Deleted<>());
  }

  private void mark(NextResult<T> terminalValue) {
    lock.lock();
    try {
      if (terminal) return;
      terminal = true;
      slot = terminalValue;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
