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
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;

public class DefaultBlockingSubscription<T> implements BlockingSubscription<T> {

  private final NextHandoff<T> handoff;
  private final Runnable canceller;
  private final ShutdownCoordinator shutdownCoordinator;
  private final AtomicBoolean done = new AtomicBoolean(false);

  public DefaultBlockingSubscription(
      NextHandoff<T> handoff, Runnable canceller, ShutdownCoordinator shutdownCoordinator) {
    this.handoff = handoff;
    this.canceller = canceller;
    this.shutdownCoordinator = shutdownCoordinator;
    shutdownCoordinator.register(this);
  }

  @Override
  public NextResult<T> next(Duration timeout) {
    if (Thread.currentThread().isInterrupted()) {
      markDone();
      return new NextResult.Timeout<>();
    }

    NextResult<T> result = handoff.pull(timeout);

    if (result.isTerminal() || Thread.currentThread().isInterrupted()) {
      markDone();
    }

    return result;
  }

  @Override
  public boolean isActive() {
    return !done.get();
  }

  @Override
  public void cancel() {
    if (markDone()) {
      canceller.run();
      // Unblock any thread currently inside next() via the handoff's own
      // wakeup mechanism. The woken pull() returns Cancelled, the caller's
      // while (sub.isActive()) check sees false, and the loop exits.
      handoff.markCancelled();
    }
  }

  /**
   * Transitions the subscription to the done state and unregisters from the shutdown coordinator.
   * Returns {@code true} on the first call that wins the transition; subsequent calls are no-ops.
   */
  private boolean markDone() {
    if (done.compareAndSet(false, true)) {
      shutdownCoordinator.unregister(this);
      return true;
    }
    return false;
  }
}
