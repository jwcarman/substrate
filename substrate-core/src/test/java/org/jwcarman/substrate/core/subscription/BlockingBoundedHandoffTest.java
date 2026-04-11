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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.NextResult;

class BlockingBoundedHandoffTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  @Test
  void pushAndPullReturnsValue() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.push("hello");
    NextResult<String> result = handoff.pull(SHORT_TIMEOUT);
    assertThat(result).isEqualTo(new NextResult.Value<>("hello"));
  }

  @Test
  void pullTimesOutWhenEmpty() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    NextResult<String> result = handoff.pull(SHORT_TIMEOUT);
    assertThat(result).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void markCancelledIsIdempotent() {
    var handoff = new BlockingBoundedHandoff<String>(10);

    // First call wins and drops a Cancelled into the queue.
    handoff.markCancelled();
    // Second call hits the compareAndSet false branch and does nothing.
    handoff.markCancelled();
    // Third call ditto, for good measure.
    handoff.markCancelled();

    // Exactly one Cancelled value is visible to the consumer; subsequent pulls
    // time out (nothing else was queued and no other marker was set).
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Cancelled.class);
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void valuesDeliveredBeforeTerminalMarker() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.push("a");
    handoff.push("b");
    handoff.push("c");
    handoff.markCompleted();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("a"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("b"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("c"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void firstTerminalMarkerWins() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markCompleted();
    handoff.markExpired();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void pushAfterMarkIsDropped() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.push("before");
    handoff.markCompleted();
    handoff.push("after-mark");

    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("before"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void pushBlocksWhenQueueIsFull() {
    var handoff = new BlockingBoundedHandoff<String>(1);
    handoff.push("first");

    var blocked = new AtomicBoolean(true);
    var started = new CountDownLatch(1);
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  started.countDown();
                  handoff.push("second");
                  blocked.set(false);
                });

    assertTimeout(
        Duration.ofSeconds(2),
        () -> {
          started.await();
          await().during(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1)).until(blocked::get);

          handoff.pull(SHORT_TIMEOUT);
          thread.join(1000);
          assertThat(blocked.get()).isFalse();
        });
  }

  @Test
  void pushAllEnqueuesAllItemsInOrder() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.pushAll(List.of("x", "y", "z"));

    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("x"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("y"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("z"));
  }

  @Test
  void constructorRejectsNonPositiveCapacity() {
    assertThatThrownBy(() -> new BlockingBoundedHandoff<String>(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BlockingBoundedHandoff<String>(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void errorTerminalIsDelivered() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    var cause = new RuntimeException("boom");
    handoff.error(cause);

    NextResult<String> result = handoff.pull(SHORT_TIMEOUT);
    assertThat(result).isInstanceOf(NextResult.Errored.class);
    assertThat(((NextResult.Errored<String>) result).cause()).isSameAs(cause);
  }

  @Test
  void deletedTerminalIsDelivered() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markDeleted();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Deleted.class);
  }

  @Test
  void expiredTerminalIsDelivered() {
    var handoff = new BlockingBoundedHandoff<String>(10);
    handoff.markExpired();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
  }

  @Test
  void pullReturnsTimeoutWhenInterrupted() {
    var handoff = new BlockingBoundedHandoff<String>(10);

    Thread.currentThread().interrupt();
    NextResult<String> result = handoff.pull(Duration.ofSeconds(10));

    assertThat(result).isInstanceOf(NextResult.Timeout.class);
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void pushSilentlyHandlesInterrupt() throws Exception {
    var handoff = new BlockingBoundedHandoff<String>(1);
    handoff.push("fill");

    var started = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  started.countDown();
                  handoff.push("blocked");
                  finished.countDown();
                });

    assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    thread.interrupt();
    assertThat(finished.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
  }
}
