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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.core.subscription.BlockingBoundedHandoff;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;

class ShutdownCoordinatorTest {

  @Test
  void stopCancelsAllRegisteredSubscriptions() {
    var coordinator = new ShutdownCoordinator();
    var canceller1 = new AtomicInteger(0);
    var canceller2 = new AtomicInteger(0);

    var sub1 =
        new DefaultBlockingSubscription<>(
            new BlockingBoundedHandoff<String>(10), canceller1::incrementAndGet, coordinator);
    var sub2 =
        new DefaultBlockingSubscription<>(
            new BlockingBoundedHandoff<String>(10), canceller2::incrementAndGet, coordinator);

    assertThat(sub1.isActive()).isTrue();
    assertThat(sub2.isActive()).isTrue();

    coordinator.stop();

    assertThat(sub1.isActive()).isFalse();
    assertThat(sub2.isActive()).isFalse();
    assertThat(canceller1.get()).isEqualTo(1);
    assertThat(canceller2.get()).isEqualTo(1);
  }

  @Test
  void stopUnblocksThreadsCurrentlyInsideNext() throws InterruptedException {
    var coordinator = new ShutdownCoordinator();
    var handoff = new BlockingBoundedHandoff<String>(10);
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    var entered = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var result = new java.util.concurrent.atomic.AtomicReference<NextResult<String>>();

    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  entered.countDown();
                  result.set(sub.next(Duration.ofMinutes(10)));
                  exited.countDown();
                });

    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    // Wait until the consumer is actually parked inside next() before we
    // fire stop() — otherwise stop() could race ahead and the test would
    // no longer cover the "unblock an in-flight next()" behavior.
    await()
        .atMost(Duration.ofSeconds(1))
        .until(
            () -> {
              var state = consumer.getState();
              return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
            });

    coordinator.stop();

    // The consumer must unblock promptly (no 10-minute wait) and observe
    // Cancelled as the terminal result.
    assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(result.get()).isInstanceOf(NextResult.Cancelled.class);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void userInitiatedCancelUnregistersFromCoordinator() {
    var coordinator = new ShutdownCoordinator();
    var canceller = new AtomicInteger(0);
    var sub =
        new DefaultBlockingSubscription<>(
            new BlockingBoundedHandoff<String>(10), canceller::incrementAndGet, coordinator);

    sub.cancel();
    // After explicit cancel, stop() should NOT invoke the canceller a second time
    coordinator.stop();

    assertThat(canceller.get()).isEqualTo(1);
  }

  @Test
  void naturalTerminationUnregistersFromCoordinator() {
    var coordinator = new ShutdownCoordinator();
    var canceller = new AtomicInteger(0);
    var handoff = new BlockingBoundedHandoff<String>(10);
    var sub = new DefaultBlockingSubscription<>(handoff, canceller::incrementAndGet, coordinator);

    // Push a terminal directly — consumer observes it on next()
    handoff.markCompleted();
    var result = sub.next(Duration.ofMillis(100));
    assertThat(result).isInstanceOf(NextResult.Completed.class);
    assertThat(sub.isActive()).isFalse();

    // Stop should now be a no-op for this subscription because it unregistered
    // itself when next() observed the terminal
    coordinator.stop();

    assertThat(canceller.get()).isZero();
  }

  @Test
  void lateRegistrationAfterStopCancelsImmediately() {
    var coordinator = new ShutdownCoordinator();
    coordinator.stop();

    var canceller = new AtomicInteger(0);
    var sub =
        new DefaultBlockingSubscription<>(
            new BlockingBoundedHandoff<String>(10), canceller::incrementAndGet, coordinator);

    // A subscription registered after the coordinator has stopped should be
    // cancelled immediately by the register() call itself.
    assertThat(sub.isActive()).isFalse();
    assertThat(canceller.get()).isEqualTo(1);
  }

  @Test
  void asyncStopInvokesCallback() throws InterruptedException {
    var coordinator = new ShutdownCoordinator();
    var sub1 =
        new DefaultBlockingSubscription<>(
            new BlockingBoundedHandoff<String>(10), () -> {}, coordinator);
    var sub2 =
        new DefaultBlockingSubscription<>(
            new BlockingBoundedHandoff<String>(10), () -> {}, coordinator);

    var callbackFired = new CountDownLatch(1);
    coordinator.stop(callbackFired::countDown);

    assertThat(callbackFired.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(sub1.isActive()).isFalse();
    assertThat(sub2.isActive()).isFalse();
  }

  @Test
  void phaseIsMaxValueSoItStopsFirst() {
    assertThat(new ShutdownCoordinator().getPhase()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void isRunningReflectsLifecycle() {
    var coordinator = new ShutdownCoordinator();
    assertThat(coordinator.isRunning()).isTrue();
    coordinator.stop();
    assertThat(coordinator.isRunning()).isFalse();
    coordinator.start();
    assertThat(coordinator.isRunning()).isTrue();
  }

  @Test
  void registryBecomesEmptyAfterAllCancellationsComplete() {
    var coordinator = new ShutdownCoordinator();
    var handoff = new BlockingBoundedHandoff<String>(10);
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    sub.cancel();

    // After cancel, the subscription should have unregistered. Registering a
    // fresh sub should still work (coordinator is still running) and stop
    // should only touch the new one.
    var secondCanceller = new AtomicInteger(0);
    var sub2 =
        new DefaultBlockingSubscription<>(
            new BlockingBoundedHandoff<String>(10), secondCanceller::incrementAndGet, coordinator);
    coordinator.stop();
    assertThat(secondCanceller.get()).isEqualTo(1);
    // sub was already cancelled; stop should not run the canceller again (done
    // was already true). This assertion is implicit — we can't directly observe
    // the registry size, but we can observe that cancellation state is sane.
    assertThat(sub.isActive()).isFalse();
    assertThat(sub2.isActive()).isFalse();
    await().atMost(Duration.ofSeconds(1)).until(() -> !coordinator.isRunning());
  }

  @Test
  void cancelThrowingDuringShutdownIsLoggedButNotPropagated() {
    var coordinator = new ShutdownCoordinator();
    coordinator.register(throwingSubscription());

    // stop() must not propagate the RuntimeException from the fan-out workers.
    assertThatNoException().isThrownBy(coordinator::stop);
  }

  @Test
  void lateRegistrationWhereCancelThrowsIsLoggedButNotPropagated() {
    var coordinator = new ShutdownCoordinator();
    coordinator.stop();

    // register() after stop() fires cancel() synchronously. If cancel() throws,
    // register() must swallow the exception rather than propagate to the caller.
    assertThatNoException().isThrownBy(() -> coordinator.register(throwingSubscription()));
  }

  @Test
  void deadlineExceededAbandonsRemainingWorkers() {
    var coordinator = new ShutdownCoordinator(Duration.ofMillis(100));
    var cancelStarted = new CountDownLatch(1);
    var neverSignalled = new CountDownLatch(1);
    coordinator.register(blockingSubscription(cancelStarted, neverSignalled));

    long start = System.nanoTime();
    coordinator.stop();
    var elapsed = Duration.ofNanos(System.nanoTime() - start);

    // The slow cancel never completes, but stop() returns after the deadline.
    assertThat(cancelStarted.getCount()).isZero();
    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void interruptDuringJoinRestoresInterruptFlag() {
    var coordinator = new ShutdownCoordinator(Duration.ofSeconds(10));
    var cancelStarted = new CountDownLatch(1);
    var neverSignalled = new CountDownLatch(1);
    coordinator.register(blockingSubscription(cancelStarted, neverSignalled));

    var stopperInterrupted = new AtomicBoolean(false);
    var stopperDone = new CountDownLatch(1);
    Thread stopper =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    coordinator.stop();
                  } finally {
                    stopperInterrupted.set(Thread.currentThread().isInterrupted());
                    stopperDone.countDown();
                  }
                });

    // Wait until the stopper is actually parked inside worker.join() before
    // interrupting it.
    await()
        .atMost(Duration.ofSeconds(1))
        .until(
            () -> {
              var state = stopper.getState();
              return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
            });
    stopper.interrupt();

    await().atMost(Duration.ofSeconds(1)).until(() -> stopperDone.getCount() == 0);
    assertThat(stopperInterrupted.get()).isTrue();
  }

  private static Subscription throwingSubscription() {
    return new Subscription() {
      @Override
      public boolean isActive() {
        return true;
      }

      @Override
      public void cancel() {
        throw new RuntimeException("boom from cancel()");
      }
    };
  }

  private static Subscription blockingSubscription(CountDownLatch started, CountDownLatch release) {
    return new Subscription() {
      @Override
      public boolean isActive() {
        return true;
      }

      @Override
      public void cancel() {
        started.countDown();
        try {
          release.await();
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
      }
    };
  }
}
