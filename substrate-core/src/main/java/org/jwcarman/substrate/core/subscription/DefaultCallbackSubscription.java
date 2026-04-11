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
 * A thin pump layer over a {@link BlockingSubscription} that converts pull-style delivery into
 * push-style. A dedicated virtual thread loops over {@code source.next(...)} and dispatches each
 * {@link NextResult} to the appropriate {@link Subscriber} method.
 *
 * <p>All state and lifecycle (cancellation, shutdown-coordinator registration, the {@code done}
 * flag, the feeder canceller) lives in the underlying {@link BlockingSubscription}. This class only
 * adds the handler thread and the dispatch switch.
 *
 * @param <T> the type of values delivered by the subscription
 */
public class DefaultCallbackSubscription<T> implements Subscription {

  private static final Log log = LogFactory.getLog(DefaultCallbackSubscription.class);
  private static final Duration MAX_POLL_DURATION = Duration.ofDays(365);

  private final BlockingSubscription<T> source;
  private final Thread handlerThread;

  public DefaultCallbackSubscription(BlockingSubscription<T> source, Subscriber<T> subscriber) {
    this.source = source;
    this.handlerThread =
        Thread.ofVirtual()
            .name("substrate-callback-handler", 0)
            .start(() -> runHandlerLoop(subscriber));
  }

  private void runHandlerLoop(Subscriber<T> subscriber) {
    while (source.isActive()) {
      NextResult<T> result = source.next(MAX_POLL_DURATION);
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
      case NextResult.Completed<T> _ -> safeRun(() -> subscriber.onCompleted(), "onCompleted");
      case NextResult.Expired<T> _ -> safeRun(() -> subscriber.onExpired(), "onExpired");
      case NextResult.Deleted<T> _ -> safeRun(() -> subscriber.onDeleted(), "onDeleted");
      case NextResult.Cancelled<T> _ -> safeRun(() -> subscriber.onCancelled(), "onCancelled");
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

  @Override
  public boolean isActive() {
    return source.isActive();
  }

  @Override
  public void cancel() {
    source.cancel();
    handlerThread.interrupt();
  }
}
