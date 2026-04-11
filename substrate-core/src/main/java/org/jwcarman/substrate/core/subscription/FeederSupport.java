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

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;

/**
 * Shared scaffolding for feeder threads across the three primitives ({@code Atom}, {@code Journal},
 * {@code Mailbox}). Encapsulates the notifier subscription, semaphore-based wake-up mechanism,
 * thread lifecycle, and exception handling that are identical across all three feeders.
 * Primitive-specific work is supplied via a {@link FeederStep} lambda.
 *
 * <p>Each primitive's {@code Default*.startFeeder} method calls {@link #start} to spawn a feeder
 * virtual thread and returns the canceller closure as the subscription's cleanup hook.
 */
public final class FeederSupport {

  private static final Log log = LogFactory.getLog(FeederSupport.class);

  private static final String DELETED_PAYLOAD = "__DELETED__";

  private FeederSupport() {}

  /**
   * Start a feeder virtual thread that drives {@code step} in a loop until the step returns {@code
   * false}, the thread is interrupted, or a terminal condition fires.
   *
   * <p>The feeder thread subscribes to the {@code notifier} for the given {@code key} and uses a
   * semaphore to wake up on notifications. On a {@code "__DELETED__"} payload, the feeder calls
   * {@code handoff.markDeleted()} and exits its loop. On any uncaught {@link RuntimeException} from
   * the step, the feeder calls {@code handoff.error(cause)} and exits. The notifier subscription is
   * always cancelled in a {@code finally} block.
   *
   * @param key the backend-qualified key this feeder is associated with — used to filter
   *     notifications
   * @param notifier the notifier SPI to subscribe to
   * @param handoff the handoff that the step will push values into; also the target of {@code
   *     markDeleted} and {@code error} when those events fire
   * @param threadName the virtual thread name prefix (e.g., {@code "substrate-atom-feeder"})
   * @param step the primitive-specific work to run on each iteration
   * @return a canceller closure that, when run, stops the feeder thread (by interrupting it) and
   *     cancels the notifier subscription
   */
  public static Runnable start(
      String key,
      NotifierSpi notifier,
      NextHandoff<?> handoff,
      String threadName,
      FeederStep step) {

    AtomicBoolean running = new AtomicBoolean(true);
    Semaphore semaphore = new Semaphore(0);

    NotifierSubscription notifierSub =
        notifier.subscribe(
            (notifiedKey, payload) ->
                handleNotification(key, notifiedKey, payload, handoff, running, semaphore));

    Thread feederThread =
        Thread.ofVirtual()
            .name(threadName, 0)
            .start(() -> runLoop(running, semaphore, handoff, step, notifierSub, threadName, key));

    return () -> {
      running.set(false);
      feederThread.interrupt();
      notifierSub.cancel();
    };
  }

  private static void handleNotification(
      String expectedKey,
      String notifiedKey,
      String payload,
      NextHandoff<?> handoff,
      AtomicBoolean running,
      Semaphore semaphore) {
    if (!expectedKey.equals(notifiedKey)) {
      return;
    }
    if (DELETED_PAYLOAD.equals(payload)) {
      handoff.markDeleted();
      running.set(false);
    }
    semaphore.release();
  }

  private static void runLoop(
      AtomicBoolean running,
      Semaphore semaphore,
      NextHandoff<?> handoff,
      FeederStep step,
      NotifierSubscription notifierSub,
      String threadName,
      String key) {
    if (log.isDebugEnabled()) {
      log.debug("Feeder '" + threadName + "' started for key '" + key + "'");
    }
    try {
      while (running.get() && !Thread.currentThread().isInterrupted()) {
        if (!step.runOnce()) {
          return;
        }
        waitForNudge(semaphore);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      log.warn("Feeder '" + threadName + "' for key '" + key + "' caught unexpected error", e);
      handoff.error(e);
    } finally {
      notifierSub.cancel();
      if (log.isDebugEnabled()) {
        log.debug("Feeder '" + threadName + "' exited for key '" + key + "'");
      }
    }
  }

  private static void waitForNudge(Semaphore semaphore) throws InterruptedException {
    if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
      semaphore.drainPermits();
    }
  }

  /**
   * One iteration of a primitive's feeder work. Invoked repeatedly by {@link FeederSupport#start}'s
   * internal loop.
   *
   * <p>Implementations capture primitive-specific state via closure from the enclosing {@code
   * startFeeder} method. Typical state includes an atomic reference to the last-known state (token
   * or checkpoint), the handoff reference, and the codec.
   *
   * <p>Implementations may catch primitive-specific exceptions (e.g., {@code
   * JournalExpiredException}, {@code MailboxExpiredException}) inside the step body to map them to
   * the appropriate handoff terminal state. Any other uncaught {@code RuntimeException} will be
   * caught by {@link FeederSupport}'s outer catch and delivered as {@code NextResult.Errored} via
   * {@code handoff.error}.
   */
  @FunctionalInterface
  public interface FeederStep {

    /**
     * Runs one iteration of the feeder's work — typically a single SPI read and zero-or-more pushes
     * into the handoff.
     *
     * @return {@code true} to continue the feeder loop; {@code false} to exit the feeder thread
     *     cleanly. Returning false is appropriate when the primitive has reached a terminal state
     *     (expired, deleted, single-delivery complete) and the step has already called the relevant
     *     {@code mark*} method on the handoff.
     */
    boolean runOnce() throws InterruptedException;
  }
}
