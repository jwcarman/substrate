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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.NextResult;

class BoundedQueueHandoffTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  @Test
  void deliverAndPollReturnsValue() {
    var h = new BoundedQueueHandoff<String>(10);
    h.deliver("hello");
    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("hello"));
  }

  @Test
  void pollTimesOutWhenEmpty() {
    var h = new BoundedQueueHandoff<String>(10);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void cancelledTerminalIsSticky() {
    var h = new BoundedQueueHandoff<String>(10);
    h.markCancelled();
    h.markCancelled();
    h.markCancelled();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Cancelled.class);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Cancelled.class);
  }

  @Test
  void valuesDeliveredBeforeTerminalMarker() {
    var h = new BoundedQueueHandoff<String>(10);
    h.deliver("a");
    h.deliver("b");
    h.deliver("c");
    h.markCompleted();

    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("a"));
    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("b"));
    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("c"));
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void firstTerminalMarkerWins() {
    var h = new BoundedQueueHandoff<String>(10);
    h.markCompleted();
    h.markExpired();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void deliverAfterMarkIsDropped() {
    var h = new BoundedQueueHandoff<String>(10);
    h.deliver("before");
    h.markCompleted();
    h.deliver("after-mark");

    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("before"));
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void deliverBlocksWhenQueueIsFull() {
    var h = new BoundedQueueHandoff<String>(1);
    h.deliver("first");

    var blocked = new AtomicBoolean(true);
    var started = new CountDownLatch(1);
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  started.countDown();
                  h.deliver("second");
                  blocked.set(false);
                });

    assertTimeout(
        Duration.ofSeconds(2),
        () -> {
          started.await();
          await().during(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1)).until(blocked::get);

          h.poll(SHORT_TIMEOUT);
          thread.join(1000);
          assertThat(blocked.get()).isFalse();
        });
  }

  @Test
  void terminalUnblocksParkedConsumer() throws Exception {
    var h = new BoundedQueueHandoff<String>(10);
    var result = new java.util.concurrent.atomic.AtomicReference<NextResult<String>>();
    var done = new CountDownLatch(1);
    Thread.ofVirtual()
        .start(
            () -> {
              result.set(h.poll(Duration.ofSeconds(5)));
              done.countDown();
            });

    // Let the consumer park.
    Thread.sleep(100);
    h.markCompleted();
    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(result.get()).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void terminalUnblocksBlockedProducer() throws Exception {
    var h = new BoundedQueueHandoff<String>(1);
    h.deliver("fill");

    var started = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    Thread.ofVirtual()
        .start(
            () -> {
              started.countDown();
              h.deliver("blocked");
              finished.countDown();
            });

    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(50);
    h.markCompleted();
    assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void constructorRejectsNonPositiveCapacity() {
    assertThatThrownBy(() -> new BoundedQueueHandoff<String>(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedQueueHandoff<String>(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void errorTerminalIsDelivered() {
    var h = new BoundedQueueHandoff<String>(10);
    var cause = new RuntimeException("boom");
    h.error(cause);

    NextResult<String> r = h.poll(SHORT_TIMEOUT);
    assertThat(r).isInstanceOf(NextResult.Errored.class);
    assertThat(((NextResult.Errored<String>) r).cause()).isSameAs(cause);
  }

  @Test
  void deletedTerminalIsDelivered() {
    var h = new BoundedQueueHandoff<String>(10);
    h.markDeleted();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Deleted.class);
  }

  @Test
  void expiredTerminalIsDelivered() {
    var h = new BoundedQueueHandoff<String>(10);
    h.markExpired();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
  }

  @Test
  void pollReturnsTimeoutWhenInterrupted() {
    var h = new BoundedQueueHandoff<String>(10);
    Thread.currentThread().interrupt();
    NextResult<String> r = h.poll(Duration.ofSeconds(10));
    assertThat(r).isInstanceOf(NextResult.Timeout.class);
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void deliverSilentlyHandlesInterrupt() throws Exception {
    var h = new BoundedQueueHandoff<String>(1);
    h.deliver("fill");

    var started = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  started.countDown();
                  h.deliver("blocked");
                  finished.countDown();
                });

    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(50);
    thread.interrupt();
    assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
  }
}
