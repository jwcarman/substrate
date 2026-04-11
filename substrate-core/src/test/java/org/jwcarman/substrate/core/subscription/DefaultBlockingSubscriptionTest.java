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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.NextResult;

class DefaultBlockingSubscriptionTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  @Test
  void nextReturnsValueAndRemainsActive() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.push("hello");
    var canceller = new AtomicInteger(0);
    var sub = new DefaultBlockingSubscription<>(handoff, canceller::incrementAndGet);

    NextResult<String> result = sub.next(SHORT_TIMEOUT);
    assertThat(result).isEqualTo(new NextResult.Value<>("hello"));
    assertThat(sub.isActive()).isTrue();
  }

  @Test
  void nextReturnsTimeoutAndRemainsActive() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {});

    NextResult<String> result = sub.next(SHORT_TIMEOUT);
    assertThat(result).isInstanceOf(NextResult.Timeout.class);
    assertThat(sub.isActive()).isTrue();
  }

  @Test
  void completedFlipsActiveToFalse() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markCompleted();
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {});

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void expiredFlipsActiveToFalse() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markExpired();
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {});

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void deletedFlipsActiveToFalse() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markDeleted();
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {});

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void erroredFlipsActiveToFalse() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.error(new RuntimeException("boom"));
    var sub = new DefaultBlockingSubscription<>(handoff, () -> {});

    sub.next(SHORT_TIMEOUT);
    assertThat(sub.isActive()).isFalse();
  }

  @Test
  void cancelFlipsActiveAndRunsCanceller() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var canceller = new AtomicInteger(0);
    var sub = new DefaultBlockingSubscription<>(handoff, canceller::incrementAndGet);

    sub.cancel();
    assertThat(sub.isActive()).isFalse();
    assertThat(canceller.get()).isEqualTo(1);
  }

  @Test
  void cancelIsIdempotent() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var canceller = new AtomicInteger(0);
    var sub = new DefaultBlockingSubscription<>(handoff, canceller::incrementAndGet);

    sub.cancel();
    sub.cancel();
    assertThat(canceller.get()).isEqualTo(2);
  }
}
