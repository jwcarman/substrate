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
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.NextResult;

public class DefaultCallbackSubscription<T> implements CallbackSubscription {

  private static final Log log = LogFactory.getLog(DefaultCallbackSubscription.class);
  private static final Duration MAX_POLL_DURATION = Duration.ofDays(365);

  private final Runnable canceller;
  private final AtomicBoolean done = new AtomicBoolean(false);
  private final Thread handlerThread;

  public DefaultCallbackSubscription(
      NextHandoff<T> handoff,
      Runnable canceller,
      Consumer<T> onNext,
      Consumer<Throwable> onError,
      Runnable onExpiration,
      Runnable onDelete,
      Runnable onComplete) {
    this.canceller = canceller;
    this.handlerThread =
        Thread.ofVirtual()
            .name("substrate-callback-handler", 0)
            .start(
                () -> runHandlerLoop(handoff, onNext, onError, onExpiration, onDelete, onComplete));
  }

  private void runHandlerLoop(
      NextHandoff<T> handoff,
      Consumer<T> onNext,
      Consumer<Throwable> onError,
      Runnable onExpiration,
      Runnable onDelete,
      Runnable onComplete) {
    while (!done.get()) {
      if (Thread.currentThread().isInterrupted()) {
        done.set(true);
        return;
      }

      NextResult<T> result = handoff.pull(MAX_POLL_DURATION);

      switch (result) {
        case NextResult.Value<T>(T value) -> {
          try {
            onNext.accept(value);
          } catch (RuntimeException e) {
            log.warn("onNext handler threw", e);
          }
        }
        case NextResult.Timeout<T> _ -> {
          /* re-check loop condition */
        }
        case NextResult.Completed<T> _ -> {
          done.set(true);
          safeRun(onComplete);
        }
        case NextResult.Expired<T> _ -> {
          done.set(true);
          safeRun(onExpiration);
        }
        case NextResult.Deleted<T> _ -> {
          done.set(true);
          safeRun(onDelete);
        }
        case NextResult.Errored<T>(Throwable cause) -> {
          done.set(true);
          safeAccept(onError, cause);
        }
      }

      if (Thread.currentThread().isInterrupted()) {
        done.set(true);
      }
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

  private static void safeAccept(Consumer<Throwable> consumer, Throwable cause) {
    if (consumer == null) return;
    try {
      consumer.accept(cause);
    } catch (RuntimeException e) {
      log.warn("onError handler threw", e);
    }
  }

  @Override
  public boolean isActive() {
    return !done.get();
  }

  @Override
  public void cancel() {
    done.set(true);
    handlerThread.interrupt();
    canceller.run();
  }
}
