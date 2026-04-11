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
package org.jwcarman.substrate.core.lifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.Subscription;
import org.springframework.context.SmartLifecycle;

/**
 * Tracks active substrate subscriptions and cancels them during Spring context shutdown.
 *
 * <p>Runs at {@link Integer#MAX_VALUE} phase so it stops <em>before</em> any other Spring {@link
 * SmartLifecycle} bean (notably the web server's graceful-shutdown hook). Cancelling subscriptions
 * releases their feeder threads and interrupts any threads currently blocked in {@code next()},
 * which drops {@code SseEmitter}-backed async servlet requests down to zero and lets Tomcat's
 * graceful shutdown return promptly.
 *
 * <p>This class is internal to {@code substrate-core}; consumers never touch it directly. It is
 * auto-registered as a Spring {@code @Bean} by {@code SubstrateAutoConfiguration}.
 */
public class ShutdownCoordinator implements SmartLifecycle {

  private static final Log log = LogFactory.getLog(ShutdownCoordinator.class);

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private final Set<Subscription> registered = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Duration totalTimeout;

  public ShutdownCoordinator() {
    this(DEFAULT_TIMEOUT);
  }

  public ShutdownCoordinator(Duration totalTimeout) {
    this.totalTimeout = totalTimeout;
  }

  /**
   * Register a subscription so it gets cancelled at context shutdown. If the coordinator has
   * already started shutting down, the subscription is cancelled synchronously on this call.
   */
  public void register(Subscription subscription) {
    if (running.get()) {
      registered.add(subscription);
    } else {
      try {
        subscription.cancel();
      } catch (RuntimeException e) {
        log.warn("cancel() threw on late registration after shutdown started", e);
      }
    }
  }

  /** Un-register a subscription. Called from the subscription's own {@code cancel()} path. */
  public void unregister(Subscription subscription) {
    registered.remove(subscription);
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public void start() {
    running.set(true);
  }

  @Override
  public void stop() {
    performShutdown();
  }

  @Override
  public void stop(Runnable callback) {
    Thread.ofVirtual()
        .name("substrate-shutdown-coordinator")
        .start(
            () -> {
              try {
                performShutdown();
              } finally {
                callback.run();
              }
            });
  }

  private void performShutdown() {
    if (!running.compareAndSet(true, false)) {
      return;
    }

    List<Subscription> snapshot = List.copyOf(registered);
    if (log.isDebugEnabled()) {
      log.debug("Cancelling " + snapshot.size() + " substrate subscription(s) at shutdown");
    }

    // Fan out: one virtual thread per subscription. cancel() is nearly instant
    // in the happy case, but a slow canceller or a stuck notifier unsubscribe
    // could block one subscription's cleanup without affecting the others.
    List<Thread> workers = new ArrayList<>(snapshot.size());
    for (Subscription subscription : snapshot) {
      workers.add(
          Thread.ofVirtual()
              .name("substrate-shutdown-cancel")
              .start(
                  () -> {
                    try {
                      subscription.cancel();
                    } catch (RuntimeException e) {
                      log.warn("cancel() threw during shutdown", e);
                    }
                  }));
    }

    // Fan in: wait for all workers to complete, bounded by a shared deadline.
    // Any worker that doesn't complete in time is abandoned; Spring's phase
    // timeout is the outer safety net.
    long deadlineNanos = System.nanoTime() + totalTimeout.toNanos();
    for (Thread worker : workers) {
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        log.warn(
            "Shutdown coordinator deadline exceeded; "
                + "abandoning remaining cancel workers after "
                + totalTimeout);
        break;
      }
      try {
        worker.join(Duration.ofNanos(remainingNanos));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }

    registered.clear();
  }
}
