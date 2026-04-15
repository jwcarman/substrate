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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.NextResult;

/**
 * Base class that factors out the shared machinery of {@link Handoff} implementations: the lock,
 * the {@code notEmpty} condition, the sticky terminal field, the {@link #poll(Duration)}
 * deadline/await loop, and the terminal-marker methods. Subclasses provide the value-storage
 * strategy by implementing {@link #takeValue()} and (optionally) overriding {@link
 * #onTerminalSet()} to wake additional conditions.
 *
 * <p>Null is used as the "empty" sentinel from {@link #takeValue()}; this is safe because handoff
 * values are non-null by contract.
 *
 * @param <T> the type of values transferred through the handoff
 */
public abstract class AbstractHandoff<T> implements Handoff<T> {

  private static final Log log = LogFactory.getLog(AbstractHandoff.class);

  /** Lock guarding all mutable state of this handoff. */
  protected final Lock lock = new ReentrantLock();

  /** Signalled when a value becomes available or a terminal is set. */
  protected final Condition notEmpty = lock.newCondition();

  /** Sticky terminal marker; once non-null, no further values will be delivered. */
  protected NextResult<T> terminal;

  /** Creates a new handoff with no pending values and no terminal set. */
  protected AbstractHandoff() {
    // no-op
  }

  /**
   * Takes the next pending value, if any. Invoked under {@link #lock}. Must return {@code null}
   * when no value is available.
   *
   * @return the next pending value, or {@code null} if empty
   */
  protected abstract T takeValue();

  /**
   * Hook invoked under {@link #lock} after a terminal has been set and {@link #notEmpty} has been
   * signalled. Subclasses override to wake additional conditions (for example, a {@code notFull}
   * condition used for backpressure). The default implementation is a no-op.
   */
  protected void onTerminalSet() {
    // no-op
  }

  @Override
  public final NextResult<T> poll(Duration timeout) {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + timeout.toNanos();
      long remaining = deadlineNanos - System.nanoTime();
      while (true) {
        T v = takeValue();
        if (v != null) {
          return new NextResult.Value<>(v);
        }
        if (terminal != null) {
          return terminal;
        }
        if (remaining <= 0) {
          return new NextResult.Timeout<>();
        }
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
  public final void error(Throwable cause) {
    mark(new NextResult.Errored<>(cause));
  }

  @Override
  public final void markCompleted() {
    mark(new NextResult.Completed<>());
  }

  @Override
  public final void markExpired() {
    mark(new NextResult.Expired<>());
  }

  @Override
  public final void markDeleted() {
    mark(new NextResult.Deleted<>());
  }

  @Override
  public final void markCancelled() {
    mark(new NextResult.Cancelled<>());
  }

  private void mark(NextResult<T> t) {
    lock.lock();
    try {
      if (terminal != null) {
        if (log.isDebugEnabled()) {
          log.debug("Handoff terminal already set (" + terminal + "); ignoring " + t);
        }
        return;
      }
      terminal = t;
      if (log.isDebugEnabled()) {
        log.debug("Handoff terminal set: " + t);
      }
      notEmpty.signalAll();
      onTerminalSet();
    } finally {
      lock.unlock();
    }
  }
}
