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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import tools.jackson.databind.json.JsonMapper;

class FeederSupportTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);
  private static final String KEY = "test-key";

  private Notifier notifier;

  @BeforeEach
  void setUp() {
    notifier =
        new DefaultNotifier(
            new InMemoryNotifier(), new JacksonCodecFactory(JsonMapper.builder().build()));
  }

  @Test
  void stepReturningTrueContinuesLoop() {
    var handoff = new SingleSlotHandoff<String>();
    var invocations = new AtomicInteger(0);

    Runnable canceller =
        FeederSupport.start(
            KEY,
            notifier::subscribeToAtom,
            handoff,
            "test-feeder",
            () -> {
              int count = invocations.incrementAndGet();
              if (count <= 3) {
                handoff.deliver("value-" + count);
                notifier.notifyAtomChanged(KEY);
              }
              return true;
            });

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(invocations.get()).isGreaterThanOrEqualTo(3));

    canceller.run();
  }

  @Test
  void stepReturningFalseExitsCleanly() {
    var handoff = new SingleSlotHandoff<String>();

    Runnable canceller =
        FeederSupport.start(
            KEY,
            notifier::subscribeToAtom,
            handoff,
            "test-feeder",
            () -> {
              handoff.deliver("only-once");
              return false;
            });

    assertThat(handoff.poll(Duration.ofSeconds(2))).isEqualTo(new NextResult.Value<>("only-once"));

    assertThat(handoff.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);

    canceller.run();
  }

  @Test
  void uncaughtRuntimeExceptionCallsHandoffError() {
    var handoff = new SingleSlotHandoff<String>();
    var cause = new RuntimeException("boom");

    Runnable canceller =
        FeederSupport.start(
            KEY,
            notifier::subscribeToAtom,
            handoff,
            "test-feeder",
            () -> {
              throw cause;
            });

    NextResult<String> result = handoff.poll(Duration.ofSeconds(2));
    assertThat(result).isInstanceOf(NextResult.Errored.class);
    assertThat(((NextResult.Errored<String>) result).cause()).isSameAs(cause);

    canceller.run();
  }

  @Test
  void deletedNotificationCallsMarkDeletedAndExits() {
    var handoff = new SingleSlotHandoff<String>();
    var stepEntered = new CountDownLatch(1);

    Runnable canceller =
        FeederSupport.start(
            KEY,
            notifier::subscribeToAtom,
            handoff,
            "test-feeder",
            () -> {
              stepEntered.countDown();
              return true;
            });

    await().atMost(Duration.ofSeconds(2)).until(() -> stepEntered.getCount() == 0);

    notifier.notifyAtomDeleted(KEY);

    NextResult<String> result = handoff.poll(Duration.ofSeconds(2));
    assertThat(result).isInstanceOf(NextResult.Deleted.class);

    canceller.run();
  }

  @Test
  void cancellerStopsFeederThread() {
    var handoff = new SingleSlotHandoff<String>();
    var invocations = new AtomicInteger(0);

    Runnable canceller =
        FeederSupport.start(
            KEY,
            notifier::subscribeToAtom,
            handoff,
            "test-feeder",
            () -> {
              invocations.incrementAndGet();
              return true;
            });

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(invocations.get()).isPositive());

    canceller.run();

    int snapshot = invocations.get();
    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(2))
        .until(() -> invocations.get() == snapshot);
  }

  @Test
  void interruptExitsLoopWithoutFiringError() {
    var handoff = new SingleSlotHandoff<String>();
    var stepEntered = new CountDownLatch(1);

    Runnable canceller =
        FeederSupport.start(
            KEY,
            notifier::subscribeToAtom,
            handoff,
            "test-feeder",
            () -> {
              stepEntered.countDown();
              return true;
            });

    await().atMost(Duration.ofSeconds(2)).until(() -> stepEntered.getCount() == 0);

    canceller.run();

    assertThat(handoff.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);
  }

  @Test
  void notificationForDifferentKeyIsIgnored() {
    var handoff = new SingleSlotHandoff<String>();
    var stepCount = new AtomicInteger(0);

    Runnable canceller =
        FeederSupport.start(
            KEY,
            notifier::subscribeToAtom,
            handoff,
            "test-feeder",
            () -> {
              stepCount.incrementAndGet();
              return true;
            });

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(stepCount.get()).isPositive());

    notifier.notifyAtomDeleted("other-key");

    assertThat(handoff.poll(SHORT_TIMEOUT)).isInstanceOf(NextResult.Timeout.class);

    canceller.run();
  }
}
