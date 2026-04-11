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
 * Shared single-slot scaffolding for handoff strategies that transfer one value at a time via a
 * lock/condition pair. Provides the pull loop (deadline-based {@code awaitNanos}) and the
 * terminal-mark implementation. Subclasses implement {@link #push push}, {@link #pushAll pushAll},
 * and {@link #consumeSlot()} to define coalescing vs. single-shot semantics.
 *
 * @param <T> the type of values transferred through the handoff
 */
public abstract class AbstractSingleSlotHandoff<T> extends AbstractHandoff<T> {

  protected final Lock lock = new ReentrantLock();
  protected final Condition notEmpty = lock.newCondition();
  protected NextResult<T> slot;
  protected boolean sealed;

  @Override
  public final NextResult<T> pull(Duration timeout) {
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
        slot = consumeSlot();
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected final void mark(NextResult<T> terminal) {
    lock.lock();
    try {
      if (sealed) return;
      sealed = true;
      slot = terminal;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the value to place in the slot after a {@link NextResult.Value} has been consumed by
   * {@link #pull pull}.
   */
  protected abstract NextResult<T> consumeSlot();
}
