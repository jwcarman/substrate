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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultCallbackSubscriptionTest {

  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

  @Test
  void onNextIsInvokedForEachValue() {
    var handoff = new CoalescingHandoff<String>();
    var received = new CopyOnWriteArrayList<String>();
    var latch = new CountDownLatch(2);

    new DefaultCallbackSubscription<>(
        handoff,
        () -> {},
        value -> {
          received.add(value);
          latch.countDown();
        },
        null,
        null,
        null,
        null);

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

    new DefaultCallbackSubscription<>(
        handoff,
        () -> {},
        value -> {
          received.add(value);
          if (value.equals("throw")) {
            throw new RuntimeException("handler error");
          }
        },
        null,
        null,
        null,
        null);

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
        new DefaultCallbackSubscription<>(
            handoff, () -> {}, value -> {}, null, null, null, completedRef::countDown);

    handoff.markCompleted();

    await().atMost(AWAIT_TIMEOUT).until(() -> completedRef.getCount() == 0);
    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onExpirationFiresOnExpired() {
    var handoff = new CoalescingHandoff<String>();
    var expiredLatch = new CountDownLatch(1);

    var sub =
        new DefaultCallbackSubscription<>(
            handoff, () -> {}, value -> {}, null, expiredLatch::countDown, null, null);

    handoff.markExpired();

    await().atMost(AWAIT_TIMEOUT).until(() -> expiredLatch.getCount() == 0);
    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void onDeleteFiresOnDeleted() {
    var handoff = new CoalescingHandoff<String>();
    var deletedLatch = new CountDownLatch(1);

    var sub =
        new DefaultCallbackSubscription<>(
            handoff, () -> {}, value -> {}, null, null, deletedLatch::countDown, null);

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
        new DefaultCallbackSubscription<>(
            handoff,
            () -> {},
            value -> {},
            cause -> {
              errorRef.set(cause);
              errorLatch.countDown();
            },
            null,
            null,
            null);

    var boom = new RuntimeException("boom");
    handoff.error(boom);

    await().atMost(AWAIT_TIMEOUT).until(() -> errorLatch.getCount() == 0);
    assertThat(errorRef.get()).isSameAs(boom);
    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void nullCallbacksAreIgnored() {
    var handoff = new CoalescingHandoff<String>();
    var sub =
        new DefaultCallbackSubscription<>(handoff, () -> {}, value -> {}, null, null, null, null);

    handoff.markCompleted();

    await().atMost(AWAIT_TIMEOUT).until(() -> !sub.isActive());
  }

  @Test
  void cancelStopsHandlerLoop() {
    var handoff = new CoalescingHandoff<String>();
    var sub =
        new DefaultCallbackSubscription<>(handoff, () -> {}, value -> {}, null, null, null, null);

    assertThat(sub.isActive()).isTrue();
    sub.cancel();

    await().atMost(Duration.ofSeconds(3)).until(() -> !sub.isActive());
  }
}
