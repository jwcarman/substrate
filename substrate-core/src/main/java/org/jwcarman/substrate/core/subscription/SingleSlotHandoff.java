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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.jwcarman.substrate.NextResult;

/**
 * Single-slot latest-wins handoff. Pending values and terminal markers live in separate fields: the
 * {@code value} slot is consumed on read and overwritten on every {@link #deliver(Object)}, and the
 * {@code terminal} slot is sticky once set. Used by Atom and Mailbox subscriptions, whose feeders
 * guarantee that only the most recent (or only) value matters.
 *
 * @param <T> the type of values transferred through the handoff
 */
public final class SingleSlotHandoff<T> implements Handoff<T> {

  private final Lock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private T value;
  private boolean hasValue;
  private NextResult<T> terminal;

  @Override
  public void deliver(T item) {
    lock.lock();
    try {
      if (terminal != null) return;
      value = item;
      hasValue = true;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public NextResult<T> poll(Duration timeout) {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + timeout.toNanos();
      long remaining = deadlineNanos - System.nanoTime();
      while (true) {
        if (hasValue) {
          T v = value;
          value = null;
          hasValue = false;
          return new NextResult.Value<>(v);
        }
        if (terminal != null) return terminal;
        if (remaining <= 0) return new NextResult.Timeout<>();
        try {
          remaining = notEmpty.awaitNanos(remaining);
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          return new NextResult.Timeout<>();
        }
      }
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

  @Override
  public void markCancelled() {
    mark(new NextResult.Cancelled<>());
  }

  private void mark(NextResult<T> t) {
    lock.lock();
    try {
      if (terminal != null) return;
      terminal = t;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
