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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.NextResult;
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

    Thread.ofVirtual()
        .start(
            () -> {
              entered.countDown();
              result.set(sub.next(Duration.ofMinutes(10)));
              exited.countDown();
            });

    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    // Give the consumer a beat to enter the blocking pull
    Thread.sleep(50);

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
}
