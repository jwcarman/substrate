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

public class DefaultBlockingSubscription<T> implements BlockingSubscription<T> {

  private final NextHandoff<T> handoff;
  private final Runnable canceller;
  private final AtomicBoolean done = new AtomicBoolean(false);

  public DefaultBlockingSubscription(NextHandoff<T> handoff, Runnable canceller) {
    this.handoff = handoff;
    this.canceller = canceller;
  }

  @Override
  public NextResult<T> next(Duration timeout) {
    if (Thread.currentThread().isInterrupted()) {
      done.set(true);
      return new NextResult.Timeout<>();
    }

    NextResult<T> result = handoff.pull(timeout);

    if (result.isTerminal()) {
      done.set(true);
    }

    if (Thread.currentThread().isInterrupted()) {
      done.set(true);
    }

    return result;
  }

  @Override
  public boolean isActive() {
    return !done.get();
  }

  @Override
  public void cancel() {
    done.set(true);
    canceller.run();
  }
}
