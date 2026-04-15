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
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.NextResult;

class SingleSlotHandoffTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  @Test
  void deliverAndPollReturnsValue() {
    var h = new SingleSlotHandoff<String>();
    h.deliver("hello");
    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("hello"));
  }

  @Test
  void rapidDeliveriesCoalesceToLastValue() {
    var h = new SingleSlotHandoff<String>();
    h.deliver("first");
    h.deliver("second");
    h.deliver("third");
    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("third"));
  }

  @Test
  void pollTimesOutWithNoDeliveries() {
    var h = new SingleSlotHandoff<String>();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void slotIsEmptyAfterValueConsumed() {
    var h = new SingleSlotHandoff<String>();
    h.deliver("value");
    h.poll(SHORT_TIMEOUT);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void terminalStateIsSticky() {
    var h = new SingleSlotHandoff<String>();
    h.markExpired();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
  }

  @Test
  void deliverAfterTerminalIsDropped() {
    var h = new SingleSlotHandoff<String>();
    h.markCompleted();
    h.deliver("too-late");
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void pendingValueDrainsBeforeTerminal() {
    var h = new SingleSlotHandoff<String>();
    h.deliver("value");
    h.markCompleted();
    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("value"));
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void deliverNeverBlocks() {
    var h = new SingleSlotHandoff<String>();
    assertTimeout(
        Duration.ofSeconds(2),
        () -> {
          for (int i = 0; i < 1000; i++) {
            h.deliver("value-" + i);
          }
        });
  }

  @Test
  void errorTerminalIsSticky() {
    var h = new SingleSlotHandoff<String>();
    var cause = new RuntimeException("boom");
    h.error(cause);
    NextResult<String> r = h.poll(SHORT_TIMEOUT);
    assertThat(r).isInstanceOf(NextResult.Errored.class);
    assertThat(((NextResult.Errored<String>) r).cause()).isSameAs(cause);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Errored.class);
  }

  @Test
  void deletedTerminalIsSticky() {
    var h = new SingleSlotHandoff<String>();
    h.markDeleted();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Deleted.class);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Deleted.class);
  }

  @Test
  void cancelledTerminalIsSticky() {
    var h = new SingleSlotHandoff<String>();
    h.markCancelled();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Cancelled.class);
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Cancelled.class);
  }

  @Test
  void cancelDrainsPendingValueFirst() {
    var h = new SingleSlotHandoff<String>();
    h.deliver("value");
    h.markCancelled();
    assertThat(h.poll(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("value"));
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Cancelled.class);
  }

  @Test
  void firstTerminalWins() {
    var h = new SingleSlotHandoff<String>();
    h.markCompleted();
    h.markExpired();
    assertThat(h.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void pollReturnsTimeoutWhenInterrupted() {
    var h = new SingleSlotHandoff<String>();
    Thread.currentThread().interrupt();
    NextResult<String> r = h.poll(Duration.ofSeconds(10));
    assertThat(r).isInstanceOf(NextResult.Timeout.class);
    assertThat(Thread.interrupted()).isTrue();
  }
}
