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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;

class DefaultBlockingSubscriptionTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  private final ShutdownCoordinator coordinator = new ShutdownCoordinator();

  @Test
  void canBeUsedInTryWithResourcesAndCancelsOnExit() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var cancellerInvocations = new AtomicInteger(0);

    try (BlockingSubscription<String> sub =
        new DefaultBlockingSubscription<>(
            handoff, cancellerInvocations::incrementAndGet, coordinator)) {
      assertThat(sub.isActive()).isTrue();
      assertThat(cancellerInvocations.get()).isZero();
    }

    // Exiting the try-with-resources block must invoke close(), which delegates
    // to cancel(), which invokes the canceller closure exactly once.
    assertThat(cancellerInvocations.get()).isEqualTo(1);
  }

  @Test
  void closeIsIdempotentAndEquivalentToCancel() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var cancellerInvocations = new AtomicInteger(0);
    var sub =
        new DefaultBlockingSubscription<>(
            handoff, cancellerInvocations::incrementAndGet, coordinator);

    sub.close();
    sub.close();
    sub.cancel();

    assertThat(cancellerInvocations.get()).isEqualTo(1);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void nextReturnsValueAndRemainsActive() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.push("hello");
    var canceller = new AtomicInteger(0);
    var sub = new DefaultBlockingSubscription<>(handoff, canceller::incrementAndGet, coordinator);

    NextResult<String> result = sub.next(SHORT_TIMEOUT);
    assertThat(result).isEqualTo(new NextResult.Value<>("hello"));
    assertThat(sub.isActive()).isTrue();
  }

  @Test
  void nextReturnsTimeoutAndRemainsActive() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    NextResult<String> result = sub.next(SHORT_TIMEOUT);
    assertThat(result).isInstanceOf(NextResult.Timeout.class);
    assertThat(sub.isActive()).isTrue();
  }

  @Test
  void completedFlipsActiveToFalse() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markCompleted();
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void expiredFlipsActiveToFalse() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markExpired();
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void deletedFlipsActiveToFalse() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markDeleted();
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void erroredFlipsActiveToFalse() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.error(new RuntimeException("boom"));
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void cancelFlipsActiveAndRunsCanceller() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var canceller = new AtomicInteger(0);
    var sub = new DefaultBlockingSubscription<>(handoff, canceller::incrementAndGet, coordinator);

    sub.cancel();
    assertThat(sub.isActive()).isFalse();
    assertThat(canceller.get()).isEqualTo(1);
  }

  @Test
  void cancelIsIdempotent() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var canceller = new AtomicInteger(0);
    var sub = new DefaultBlockingSubscription<>(handoff, canceller::incrementAndGet, coordinator);

    sub.cancel();
    sub.cancel();
    sub.cancel();
    // Per the Subscription contract, cancel() on an already-inactive subscription
    // is a no-op. The canceller closure must run exactly once across any number
    // of cancel() invocations.
    assertThat(canceller.get()).isEqualTo(1);
    assertThat(sub.isActive()).isFalse();
  }

  // --- isTerminal tests ---

  @Test
  void valueIsNotTerminal() {
    assertThat(new NextResult.Value<>("x").isTerminal()).isFalse();
  }

  @Test
  void timeoutIsNotTerminal() {
    assertThat(new NextResult.Timeout<>().isTerminal()).isFalse();
  }

  @Test
  void completedIsTerminal() {
    assertThat(new NextResult.Completed<>().isTerminal()).isTrue();
  }

  @Test
  void expiredIsTerminal() {
    assertThat(new NextResult.Expired<>().isTerminal()).isTrue();
  }

  @Test
  void deletedIsTerminal() {
    assertThat(new NextResult.Deleted<>().isTerminal()).isTrue();
  }

  @Test
  void erroredIsTerminal() {
    assertThat(new NextResult.Errored<>(new RuntimeException("x")).isTerminal()).isTrue();
  }

  @Test
  void cancelledIsTerminal() {
    assertThat(new NextResult.Cancelled<>().isTerminal()).isTrue();
  }

  // --- Interrupt handling tests ---

  @Test
  void preInterruptedThreadReturnsTimeoutAndFlipsDone() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    Thread.currentThread().interrupt();
    try {
      NextResult<String> result = sub.next(Duration.ofSeconds(30));
      assertThat(result).isInstanceOf(NextResult.Timeout.class);
      assertThat(sub.isActive()).isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void interruptDuringPullFlipsDone() throws Exception {
    var handoff = new CoalescingHandoff<String>();
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);
    var resultRef = new AtomicReference<NextResult<String>>();
    var latch = new CountDownLatch(1);

    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  resultRef.set(sub.next(Duration.ofSeconds(30)));
                  latch.countDown();
                });

    await()
        .atMost(Duration.ofSeconds(1))
        .until(
            () -> {
              var s = consumer.getState();
              return s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING;
            });
    consumer.interrupt();

    assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(resultRef.get()).isInstanceOf(NextResult.Timeout.class);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void consumerLoopExitsCleanlyUnderInterrupt() throws Exception {
    var handoff = new CoalescingHandoff<String>();
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);
    var loopExited = new AtomicBoolean(false);

    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (sub.isActive()) {
                    sub.next(Duration.ofSeconds(30));
                  }
                  loopExited.set(true);
                });

    await()
        .atMost(Duration.ofSeconds(1))
        .until(
            () -> {
              var s = consumer.getState();
              return s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING;
            });
    consumer.interrupt();
    consumer.join(500);

    assertThat(loopExited.get()).isTrue();
    assertThat(consumer.isAlive()).isFalse();
  }

  @Test
  void valueDoesNotFlipActive() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.push("hello");
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isTrue();
  }

  @Test
  void timeoutWithoutInterruptDoesNotFlipActive() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isTrue();
  }
}
