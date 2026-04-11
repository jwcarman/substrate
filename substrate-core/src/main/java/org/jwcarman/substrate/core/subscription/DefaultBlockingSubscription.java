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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.Subscriber;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;

public class DefaultBlockingSubscription<T> implements BlockingSubscription<T> {

  private static final Duration MAX_POLL_DURATION = Duration.ofDays(365);
  private static final Log log = LogFactory.getLog(DefaultBlockingSubscription.class);

  private final NextHandoff<T> handoff;
  private final Runnable canceller;
  private final ShutdownCoordinator shutdownCoordinator;
  private final AtomicBoolean done = new AtomicBoolean(false);

  private volatile Thread pumpThread;

  public DefaultBlockingSubscription(
      NextHandoff<T> handoff, Runnable canceller, ShutdownCoordinator shutdownCoordinator) {
    this.handoff = handoff;
    this.canceller = canceller;
    this.shutdownCoordinator = shutdownCoordinator;
    shutdownCoordinator.register(this);
  }

  /** Test-friendly convenience constructor with a throwaway {@link ShutdownCoordinator}. */
  public DefaultBlockingSubscription(NextHandoff<T> handoff, Runnable canceller) {
    this(handoff, canceller, new ShutdownCoordinator());
  }

  public synchronized Subscription start(Subscriber<T> subscriber) {
    if (done.get()) {
      safeRun(subscriber::onCancelled, "onCancelled");
      return this;
    }
    this.pumpThread =
        Thread.ofVirtual().name("substrate-callback-handler", 0).start(() -> pumpLoop(subscriber));
    return this;
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
  public synchronized void cancel() {
    if (markDone()) {
      canceller.run();
      handoff.markCancelled();
      Thread t = pumpThread;
      if (t != null) {
        t.interrupt();
      }
    }
  }

  private boolean markDone() {
    if (done.compareAndSet(false, true)) {
      shutdownCoordinator.unregister(this);
      return true;
    }
    return false;
  }

  private void pumpLoop(Subscriber<T> subscriber) {
    while (isActive()) {
      NextResult<T> result = next(MAX_POLL_DURATION);
      dispatch(result, subscriber);
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
    }
  }

  private void dispatch(NextResult<T> result, Subscriber<T> subscriber) {
    switch (result) {
      case NextResult.Value<T>(T value) -> safeOnNext(subscriber, value);
      case NextResult.Timeout<T> _ -> {
        /* re-check loop condition */
      }
      case NextResult.Completed<T> _ -> safeRun(subscriber::onCompleted, "onCompleted");
      case NextResult.Expired<T> _ -> safeRun(subscriber::onExpired, "onExpired");
      case NextResult.Deleted<T> _ -> safeRun(subscriber::onDeleted, "onDeleted");
      case NextResult.Cancelled<T> _ -> safeRun(subscriber::onCancelled, "onCancelled");
      case NextResult.Errored<T>(Throwable cause) ->
          safeRun(() -> subscriber.onError(cause), "onError");
    }
  }

  private static <T> void safeOnNext(Subscriber<T> subscriber, T value) {
    try {
      subscriber.onNext(value);
    } catch (RuntimeException e) {
      log.warn("onNext handler threw", e);
    }
  }

  private static void safeRun(Runnable action, String label) {
    try {
      action.run();
    } catch (RuntimeException e) {
      log.warn(label + " handler threw", e);
    }
  }
}
