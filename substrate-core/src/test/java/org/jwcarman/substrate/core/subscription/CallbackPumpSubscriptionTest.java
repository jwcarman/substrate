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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.Subscriber;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;

class CallbackPumpSubscriptionTest {

  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

  private Subscription startPump(NextHandoff<String> handoff, Subscriber<String> subscriber) {
    var source = new DefaultBlockingSubscription<>(handoff, () -> {}, new ShutdownCoordinator());
    return new CallbackPumpSubscription<>(source, subscriber);
  }

  @Test
  void onNextIsInvokedForEachValue() {
    var handoff = new CoalescingHandoff<String>();
    var received = new CopyOnWriteArrayList<String>();
    var latch = new CountDownLatch(2);

    startPump(
        handoff,
        value -> {
          received.add(value);
          latch.countDown();
        });

    handoff.push("first");
    await().atMost(AWAIT_TIMEOUT).until(() -> received.size() >= 1);
    handoff.push("second");

    await().atMost(AWAIT_TIMEOUT).until(() -> received.size() >= 2);
    assertThat(received).contains("first", "second");
  }

  @Test
  void onNextExceptionDoesNotStopDelivery() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var received = new CopyOnWriteArrayList<String>();

    startPump(
        handoff,
        new Subscriber<>() {
          @Override
          public void onNext(String value) {
            received.add(value);
            if (value.equals("throw")) {
              throw new RuntimeException("handler error");
            }
          }
        });

    handoff.push("throw");
    handoff.push("after-throw");

    await().atMost(AWAIT_TIMEOUT).until(() -> received.size() >= 2);
    assertThat(received).containsExactly("throw", "after-throw");
  }

  @Test
  void onCompleteFiresOnCompletion() {
    var handoff = new CoalescingHandoff<String>();
    var completedRef = new CountDownLatch(1);

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onCompleted() {
                completedRef.countDown();
              }
            });

    handoff.markCompleted();

    await().atMost(AWAIT_TIMEOUT).until(() -> completedRef.getCount() == 0);
    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onExpirationFiresOnExpired() {
    var handoff = new CoalescingHandoff<String>();
    var expiredLatch = new CountDownLatch(1);

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onExpired() {
                expiredLatch.countDown();
              }
            });

    handoff.markExpired();

    await().atMost(AWAIT_TIMEOUT).until(() -> expiredLatch.getCount() == 0);
    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onDeleteFiresOnDeleted() {
    var handoff = new CoalescingHandoff<String>();
    var deletedLatch = new CountDownLatch(1);

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onDeleted() {
                deletedLatch.countDown();
              }
            });

    handoff.markDeleted();

    await().atMost(AWAIT_TIMEOUT).until(() -> deletedLatch.getCount() == 0);
    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onErrorFiresOnErrored() {
    var handoff = new CoalescingHandoff<String>();
    var errorRef = new AtomicReference<Throwable>();
    var errorLatch = new CountDownLatch(1);

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onError(Throwable cause) {
                errorRef.set(cause);
                errorLatch.countDown();
              }
            });

    var boom = new RuntimeException("boom");
    handoff.error(boom);

    await().atMost(AWAIT_TIMEOUT).until(() -> errorLatch.getCount() == 0);
    assertThat(errorRef.get()).isSameAs(boom);
    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onCancelledFiresOnCancelled() {
    var handoff = new CoalescingHandoff<String>();
    var cancelledLatch = new CountDownLatch(1);

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onCancelled() {
                cancelledLatch.countDown();
              }
            });

    handoff.markCancelled();

    await().atMost(AWAIT_TIMEOUT).until(() -> cancelledLatch.getCount() == 0);
    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void defaultSubscriberHandlersAreNoOps() {
    var handoff = new CoalescingHandoff<String>();
    var sub = startPump(handoff, value -> {});

    handoff.markCompleted();

    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void cancelStopsHandlerLoop() {
    var handoff = new CoalescingHandoff<String>();
    var sub = startPump(handoff, value -> {});

    assertThat(sub.isActive()).isTrue();
    sub.cancel();

    await().atMost(Duration.ofMillis(500)).until(() -> !sub.isActive());
  }

  @Test
  void cancelOnIdleSubscriptionExitsQuickly() {
    var handoff = new CoalescingHandoff<String>();
    var sub = startPump(handoff, value -> {});

    await().atMost(Duration.ofMillis(200)).until(sub::isActive);

    long start = System.nanoTime();
    sub.cancel();
    await().atMost(Duration.ofMillis(200)).until(() -> !sub.isActive());
    long elapsed = System.nanoTime() - start;

    assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofMillis(200));
  }

  @Test
  void idleSubscriptionDoesNoPeriodicWork() {
    var handoff = new CoalescingHandoff<String>();
    var pullCount = new AtomicInteger(0);

    var countingHandoff =
        new NextHandoff<String>() {
          @Override
          public void push(String item) {
            handoff.push(item);
          }

          @Override
          public void pushAll(java.util.List<String> items) {
            handoff.pushAll(items);
          }

          @Override
          public org.jwcarman.substrate.NextResult<String> pull(Duration timeout) {
            pullCount.incrementAndGet();
            return handoff.pull(timeout);
          }

          @Override
          public void error(Throwable cause) {
            handoff.error(cause);
          }

          @Override
          public void markCompleted() {
            handoff.markCompleted();
          }

          @Override
          public void markExpired() {
            handoff.markExpired();
          }

          @Override
          public void markDeleted() {
            handoff.markDeleted();
          }

          @Override
          public void markCancelled() {
            handoff.markCancelled();
          }
        };

    var sub = startPump(countingHandoff, value -> {});

    await().atMost(Duration.ofMillis(200)).until(sub::isActive);

    int pullsBefore = pullCount.get();
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(4))
        .until(() -> (pullCount.get() - pullsBefore) <= 1);

    sub.cancel();
    await().atMost(Duration.ofMillis(500)).until(() -> !sub.isActive());
  }

  @Test
  void externalInterruptExitsHandlerLoop() throws Exception {
    var handoff = new CoalescingHandoff<String>();
    var threadRef = new AtomicReference<Thread>();
    var started = new CountDownLatch(1);
    var received = new CopyOnWriteArrayList<String>();

    var capturingHandoff =
        new NextHandoff<String>() {
          @Override
          public void push(String item) {
            handoff.push(item);
          }

          @Override
          public void pushAll(java.util.List<String> items) {
            handoff.pushAll(items);
          }

          @Override
          public org.jwcarman.substrate.NextResult<String> pull(Duration timeout) {
            threadRef.compareAndSet(null, Thread.currentThread());
            started.countDown();
            return handoff.pull(timeout);
          }

          @Override
          public void error(Throwable cause) {
            handoff.error(cause);
          }

          @Override
          public void markCompleted() {
            handoff.markCompleted();
          }

          @Override
          public void markExpired() {
            handoff.markExpired();
          }

          @Override
          public void markDeleted() {
            handoff.markDeleted();
          }

          @Override
          public void markCancelled() {
            handoff.markCancelled();
          }
        };

    var sub = startPump(capturingHandoff, received::add);

    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    threadRef.get().interrupt();

    await().atMost(Duration.ofMillis(500)).until(() -> !sub.isActive());

    handoff.push("after-interrupt");
    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofMillis(500))
        .until(() -> !received.contains("after-interrupt"));
  }

  @Test
  void interruptDoesNotFireOnError() throws Exception {
    var handoff = new CoalescingHandoff<String>();
    var threadRef = new AtomicReference<Thread>();
    var started = new CountDownLatch(1);
    var errorFired = new AtomicBoolean(false);

    var capturingHandoff =
        new NextHandoff<String>() {
          @Override
          public void push(String item) {
            handoff.push(item);
          }

          @Override
          public void pushAll(java.util.List<String> items) {
            handoff.pushAll(items);
          }

          @Override
          public org.jwcarman.substrate.NextResult<String> pull(Duration timeout) {
            threadRef.compareAndSet(null, Thread.currentThread());
            started.countDown();
            return handoff.pull(timeout);
          }

          @Override
          public void error(Throwable cause) {
            handoff.error(cause);
          }

          @Override
          public void markCompleted() {
            handoff.markCompleted();
          }

          @Override
          public void markExpired() {
            handoff.markExpired();
          }

          @Override
          public void markDeleted() {
            handoff.markDeleted();
          }

          @Override
          public void markCancelled() {
            handoff.markCancelled();
          }
        };

    startPump(
        capturingHandoff,
        new Subscriber<>() {
          @Override
          public void onNext(String value) {}

          @Override
          public void onError(Throwable cause) {
            errorFired.set(true);
          }
        });

    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    threadRef.get().interrupt();

    await()
        .during(Duration.ofMillis(200))
        .atMost(Duration.ofMillis(500))
        .until(() -> !errorFired.get());
  }

  @Test
  void onCompleteHandlerExceptionIsSwallowed() {
    var handoff = new CoalescingHandoff<String>();

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onCompleted() {
                throw new RuntimeException("onComplete blew up");
              }
            });

    handoff.markCompleted();

    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onExpirationHandlerExceptionIsSwallowed() {
    var handoff = new CoalescingHandoff<String>();

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onExpired() {
                throw new RuntimeException("onExpiration blew up");
              }
            });

    handoff.markExpired();

    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onDeleteHandlerExceptionIsSwallowed() {
    var handoff = new CoalescingHandoff<String>();

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onDeleted() {
                throw new RuntimeException("onDelete blew up");
              }
            });

    handoff.markDeleted();

    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onErrorHandlerExceptionIsSwallowed() {
    var handoff = new CoalescingHandoff<String>();

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onError(Throwable cause) {
                throw new RuntimeException("onError blew up");
              }
            });

    handoff.error(new RuntimeException("original"));

    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onCancelledHandlerExceptionIsSwallowed() {
    var handoff = new CoalescingHandoff<String>();

    var sub =
        startPump(
            handoff,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onCancelled() {
                throw new RuntimeException("onCancelled blew up");
              }
            });

    handoff.markCancelled();

    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void valuePushedWhileParkedWakesHandlerImmediately() {
    var handoff = new CoalescingHandoff<String>();
    var received = new CopyOnWriteArrayList<String>();

    startPump(handoff, received::add);

    await().atMost(Duration.ofMillis(200)).pollInterval(Duration.ofMillis(10)).until(() -> true);

    handoff.push("wake-up");

    await()
        .atMost(Duration.ofMillis(200))
        .pollInterval(Duration.ofMillis(10))
        .until(() -> received.contains("wake-up"));
  }

  @Test
  void wrappingAnAlreadyCancelledSourceStillDeliversOnCancelled() {
    var handoff = new CoalescingHandoff<String>();
    var coordinator = new ShutdownCoordinator();
    var source = new DefaultBlockingSubscription<>(handoff, () -> {}, coordinator);

    // Cancel the source BEFORE wrapping it in the pump — the pump's first
    // call to next() should observe the handoff's Cancelled marker and
    // dispatch onCancelled to the subscriber.
    source.cancel();

    var cancelledCount = new AtomicInteger(0);
    var latch = new CountDownLatch(1);

    var sub =
        new CallbackPumpSubscription<>(
            source,
            new Subscriber<>() {
              @Override
              public void onNext(String value) {}

              @Override
              public void onCancelled() {
                cancelledCount.incrementAndGet();
                latch.countDown();
              }
            });

    await().atMost(Duration.ofSeconds(1)).until(() -> latch.getCount() == 0);
    await().atMost(Duration.ofMillis(500)).until(() -> !sub.isActive());
    assertThat(cancelledCount.get()).isEqualTo(1);
  }
}
