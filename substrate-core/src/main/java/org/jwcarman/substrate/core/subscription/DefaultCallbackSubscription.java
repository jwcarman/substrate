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
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.NextResult;

/**
 * A thin pump layer over a {@link BlockingSubscription} that converts pull-style delivery into
 * push-style. A dedicated virtual thread loops over {@code source.next(...)} and dispatches each
 * {@link NextResult} to the appropriate handler.
 *
 * <p>All state and lifecycle (cancellation, shutdown-coordinator registration, the {@code done}
 * flag, the feeder canceller) lives in the underlying {@link BlockingSubscription}. This class only
 * adds the handler thread and the dispatch switch.
 *
 * @param <T> the type of values delivered by the subscription
 */
public class DefaultCallbackSubscription<T> implements CallbackSubscription {

  private static final Log log = LogFactory.getLog(DefaultCallbackSubscription.class);
  private static final Duration MAX_POLL_DURATION = Duration.ofDays(365);

  private final BlockingSubscription<T> source;
  private final Thread handlerThread;

  public DefaultCallbackSubscription(
      BlockingSubscription<T> source, Consumer<T> onNext, LifecycleCallbacks<T> callbacks) {
    this.source = source;
    this.handlerThread =
        Thread.ofVirtual()
            .name("substrate-callback-handler", 0)
            .start(() -> runHandlerLoop(onNext, callbacks));
  }

  /**
   * Legacy test-friendly convenience constructor. Constructs a {@link DefaultBlockingSubscription}
   * from the raw handoff + canceller and wraps it in the callback flavor.
   */
  public DefaultCallbackSubscription(
      NextHandoff<T> handoff,
      Runnable canceller,
      Consumer<T> onNext,
      Consumer<Throwable> onError,
      Runnable onExpiration,
      Runnable onDelete,
      Runnable onComplete) {
    this(
        new DefaultBlockingSubscription<>(handoff, canceller),
        onNext,
        new LifecycleCallbacks<>(onError, onExpiration, onDelete, onComplete, null));
  }

  private void runHandlerLoop(Consumer<T> onNext, LifecycleCallbacks<T> callbacks) {
    while (source.isActive()) {
      NextResult<T> result = source.next(MAX_POLL_DURATION);
      dispatch(result, onNext, callbacks);
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
    }
  }

  private void dispatch(NextResult<T> result, Consumer<T> onNext, LifecycleCallbacks<T> callbacks) {
    switch (result) {
      case NextResult.Value<T>(T value) -> safeAccept(onNext, value, "onNext");
      case NextResult.Timeout<T> _ -> {
        /* re-check loop condition */
      }
      case NextResult.Completed<T> _ -> safeRun(callbacks.onComplete());
      case NextResult.Expired<T> _ -> safeRun(callbacks.onExpiration());
      case NextResult.Deleted<T> _ -> safeRun(callbacks.onDelete());
      case NextResult.Cancelled<T> _ -> safeRun(callbacks.onCancel());
      case NextResult.Errored<T>(Throwable cause) ->
          safeAcceptThrowable(callbacks.onError(), cause);
    }
  }

  private static <T> void safeAccept(Consumer<T> consumer, T value, String label) {
    if (consumer == null) return;
    try {
      consumer.accept(value);
    } catch (RuntimeException e) {
      log.warn(label + " handler threw", e);
    }
  }

  private static void safeAcceptThrowable(Consumer<Throwable> consumer, Throwable cause) {
    if (consumer == null) return;
    try {
      consumer.accept(cause);
    } catch (RuntimeException e) {
      log.warn("onError handler threw", e);
    }
  }

  private static void safeRun(Runnable runnable) {
    if (runnable == null) return;
    try {
      runnable.run();
    } catch (RuntimeException e) {
      log.warn("Lifecycle handler threw", e);
    }
  }

  @Override
  public boolean isActive() {
    return source.isActive();
  }

  @Override
  public void cancel() {
    // Cancelling the underlying blocking subscription is the real teardown —
    // it marks the handoff, which unblocks our handler thread's next() call.
    // Interrupting the handler thread is a belt-and-suspenders wake-up for the
    // rare case where cancel() is called while the handler is busy running a
    // user callback (not parked in next()).
    source.cancel();
    handlerThread.interrupt();
  }
}
