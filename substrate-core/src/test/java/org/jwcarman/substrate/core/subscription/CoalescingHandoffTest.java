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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.NextResult;

class CoalescingHandoffTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  @Test
  void pushAndPullReturnsValue() {
    var handoff = new CoalescingHandoff<String>();
    handoff.push("hello");
    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("hello"));
  }

  @Test
  void rapidPushesCoalesceToLastValue() {
    var handoff = new CoalescingHandoff<String>();
    handoff.push("first");
    handoff.push("second");
    handoff.push("third");

    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("third"));
  }

  @Test
  void pullTimesOutWithNoPushes() {
    var handoff = new CoalescingHandoff<String>();
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void slotIsEmptyAfterValueConsumed() {
    var handoff = new CoalescingHandoff<String>();
    handoff.push("value");
    handoff.pull(SHORT_TIMEOUT);
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void terminalStateIsSticky() {
    var handoff = new CoalescingHandoff<String>();
    handoff.markExpired();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
  }

  @Test
  void pushAfterTerminalIsDropped() {
    var handoff = new CoalescingHandoff<String>();
    handoff.markCompleted();
    handoff.push("too-late");

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void pushAllCoalescesToLastItem() {
    var handoff = new CoalescingHandoff<String>();
    handoff.pushAll(List.of("a", "b", "c"));

    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("c"));
  }

  @Test
  void pushNeverBlocks() {
    var handoff = new CoalescingHandoff<String>();
    assertTimeout(
        Duration.ofSeconds(2),
        () -> {
          for (int i = 0; i < 1000; i++) {
            handoff.push("value-" + i);
          }
        });
  }

  @Test
  void errorTerminalIsSticky() {
    var handoff = new CoalescingHandoff<String>();
    var cause = new RuntimeException("boom");
    handoff.error(cause);

    NextResult<String> result = handoff.pull(SHORT_TIMEOUT);
    assertThat(result).isInstanceOf(NextResult.Errored.class);
    assertThat(((NextResult.Errored<String>) result).cause()).isSameAs(cause);

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Errored.class);
  }

  @Test
  void deletedTerminalIsSticky() {
    var handoff = new CoalescingHandoff<String>();
    handoff.markDeleted();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Deleted.class);
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Deleted.class);
  }

  @Test
  void pushAllWithEmptyListIsNoOp() {
    var handoff = new CoalescingHandoff<String>();
    handoff.pushAll(List.of());
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }
}
