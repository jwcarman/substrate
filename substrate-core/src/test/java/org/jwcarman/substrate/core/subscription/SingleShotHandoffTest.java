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

class SingleShotHandoffTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  @Test
  void pushAndPullReturnsValue() {
    var handoff = new SingleShotHandoff<String>();
    handoff.push("hello");
    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("hello"));
  }

  @Test
  void autoTransitionsToCompletedAfterValueConsumed() {
    var handoff = new SingleShotHandoff<String>();
    handoff.push("hello");
    handoff.pull(SHORT_TIMEOUT);

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void completedIsSticky() {
    var handoff = new SingleShotHandoff<String>();
    handoff.push("hello");
    handoff.pull(SHORT_TIMEOUT);

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void secondPushIsDropped() {
    var handoff = new SingleShotHandoff<String>();
    handoff.push("first");
    handoff.push("second");

    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("first"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void markExpiredBeforePushDeliversExpired() {
    var handoff = new SingleShotHandoff<String>();
    handoff.markExpired();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Expired.class);
  }

  @Test
  void markExpiredAfterPushIsDropped() {
    var handoff = new SingleShotHandoff<String>();
    handoff.push("value");
    handoff.markExpired();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("value"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void pushAllKeepsOnlyFirst() {
    var handoff = new SingleShotHandoff<String>();
    handoff.pushAll(List.of("a", "b", "c"));

    assertThat(handoff.pull(SHORT_TIMEOUT)).isEqualTo(new NextResult.Value<>("a"));
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Completed.class);
  }

  @Test
  void pushNeverBlocks() {
    var handoff = new SingleShotHandoff<String>();
    assertTimeout(
        Duration.ofSeconds(2),
        () -> {
          handoff.push("only-one");
          for (int i = 0; i < 100; i++) {
            handoff.push("dropped-" + i);
          }
        });
  }

  @Test
  void pullTimesOutWithNoPush() {
    var handoff = new SingleShotHandoff<String>();
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void errorTerminalBeforePush() {
    var handoff = new SingleShotHandoff<String>();
    var cause = new RuntimeException("boom");
    handoff.error(cause);

    NextResult<String> result = handoff.pull(SHORT_TIMEOUT);
    assertThat(result).isInstanceOf(NextResult.Errored.class);
    assertThat(((NextResult.Errored<String>) result).cause()).isSameAs(cause);
  }

  @Test
  void deletedTerminalBeforePush() {
    var handoff = new SingleShotHandoff<String>();
    handoff.markDeleted();

    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Deleted.class);
  }

  @Test
  void pushAllWithEmptyListIsNoOp() {
    var handoff = new SingleShotHandoff<String>();
    handoff.pushAll(List.of());
    assertThat(handoff.pull(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }
}
