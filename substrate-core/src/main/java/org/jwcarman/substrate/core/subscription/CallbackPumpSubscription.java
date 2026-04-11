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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.Subscriber;
import org.jwcarman.substrate.Subscription;

/**
 * A thin push-style wrapper around a {@link BlockingSubscription} that dispatches each {@link
 * NextResult} to a {@link Subscriber} on a dedicated virtual thread.
 *
 * <p>All lifecycle state (the {@code done} flag, the feeder canceller, shutdown-coordinator
 * registration, the handoff wakeup) lives in the wrapped {@code BlockingSubscription}. This class
 * only adds the pump thread and the dispatch switch.
 *
 * <p>The pump loop is intentionally a {@code static} method that takes {@code source} and {@code
 * subscriber} as parameters rather than reading them from instance fields. The lambda used to start
 * the virtual thread therefore captures two locals — not {@code this} — so the pump thread never
 * touches a half-constructed outer object. No {@code this}-escape from the constructor.
 *
 * @param <T> the type of values delivered to the subscriber
 */
public final class CallbackPumpSubscription<T> implements Subscription {

  private static final Log log = LogFactory.getLog(CallbackPumpSubscription.class);
  private static final Duration MAX_POLL_DURATION = Duration.ofDays(365);

  private final BlockingSubscription<T> source;
  private final Thread pumpThread;

  public CallbackPumpSubscription(BlockingSubscription<T> source, Subscriber<T> subscriber) {
    this.source = source;
    // Capture source + subscriber as LOCALS — the lambda never touches `this`.
    this.pumpThread =
        Thread.ofVirtual()
            .name("substrate-callback-handler", 0)
            .start(() -> pumpLoop(source, subscriber));
  }

  @Override
  public boolean isActive() {
    return source.isActive();
  }

  @Override
  public void cancel() {
    // Cancelling the underlying blocking subscription is the real teardown —
    // it marks the handoff, which unblocks our pump thread's next() call.
    // Interrupting the pump thread is a belt-and-suspenders wake-up for the
    // rare case where cancel() is called while the pump is busy running a
    // user callback (not parked in next()).
    source.cancel();
    pumpThread.interrupt();
  }

  private static <T> void pumpLoop(BlockingSubscription<T> source, Subscriber<T> subscriber) {
    // do-while so the first pull always happens — if the source was already
    // cancelled or terminated before the pump thread started, the handoff
    // will have a terminal marker waiting and we'll dispatch it on the first
    // iteration. A plain while(isActive()) would skip the loop entirely in
    // that case and the subscriber would never see onCancelled / onCompleted.
    do {
      NextResult<T> result = source.next(MAX_POLL_DURATION);
      dispatch(result, subscriber);
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
    } while (source.isActive());
  }

  private static <T> void dispatch(NextResult<T> result, Subscriber<T> subscriber) {
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
