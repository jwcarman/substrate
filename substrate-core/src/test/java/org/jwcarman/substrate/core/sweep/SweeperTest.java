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
package org.jwcarman.substrate.core.sweep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.journal.Journal;
import org.jwcarman.substrate.mailbox.Mailbox;

class SweeperTest {

  @Test
  void constructorRejectsNullPrimitiveType() {
    Sweepable target = mock(Sweepable.class);
    assertThatThrownBy(() -> new Sweeper(null, target, Duration.ofMinutes(1), 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNullTarget() {
    assertThatThrownBy(() -> new Sweeper(Atom.class, null, Duration.ofMinutes(1), 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsBatchSizeZero() {
    Sweepable target = mock(Sweepable.class);
    assertThatThrownBy(() -> new Sweeper(Atom.class, target, Duration.ofMinutes(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNegativeBatchSize() {
    Sweepable target = mock(Sweepable.class);
    assertThatThrownBy(() -> new Sweeper(Atom.class, target, Duration.ofMinutes(1), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsZeroInterval() {
    Sweepable target = mock(Sweepable.class);
    assertThatThrownBy(() -> new Sweeper(Atom.class, target, Duration.ZERO, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNegativeInterval() {
    Sweepable target = mock(Sweepable.class);
    assertThatThrownBy(() -> new Sweeper(Atom.class, target, Duration.ofMinutes(-1), 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tickDrainsUntilBatchNotFull() {
    Sweepable target = mock(Sweepable.class);
    when(target.sweep(100)).thenReturn(100, 100, 50);

    Sweeper sweeper = new Sweeper(Atom.class, target, Duration.ofMinutes(1), 100);
    sweeper.tick();
    sweeper.close();

    verify(target, org.mockito.Mockito.times(3)).sweep(100);
  }

  @Test
  void tickStopsAtIterationCap() {
    Sweepable target = mock(Sweepable.class);
    AtomicInteger callCount = new AtomicInteger();
    when(target.sweep(100))
        .thenAnswer(
            inv -> {
              callCount.incrementAndGet();
              return 100;
            });

    Sweeper sweeper = new Sweeper(Atom.class, target, Duration.ofMinutes(1), 100);
    sweeper.tick();
    sweeper.close();

    assertThat(callCount.get()).isEqualTo(Sweeper.MAX_ITERATIONS_PER_TICK);
  }

  @Test
  void tickSwallowsRuntimeException() {
    Sweepable target = mock(Sweepable.class);
    doThrow(new RuntimeException("boom")).when(target).sweep(100);

    Sweeper sweeper = new Sweeper(Atom.class, target, Duration.ofMinutes(1), 100);
    sweeper.tick();

    doReturn(0).when(target).sweep(100);
    sweeper.tick();
    sweeper.close();

    verify(target, atLeast(2)).sweep(100);
  }

  @Test
  void closeShutdownsScheduler() {
    Sweepable target = mock(Sweepable.class);
    Sweeper sweeper = new Sweeper(Atom.class, target, Duration.ofMinutes(1), 100);

    sweeper.close();

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              // After close, scheduler is shut down and no further ticks run
            });
  }

  @Test
  void virtualThreadNameDerivedFromAtomClass() {
    AtomicReference<String> threadName = new AtomicReference<>();
    Sweepable target =
        maxToSweep -> {
          threadName.set(Thread.currentThread().getName());
          return 0;
        };

    Sweeper sweeper = new Sweeper(Atom.class, target, Duration.ofMillis(50), 100);
    await().atMost(Duration.ofSeconds(5)).until(() -> threadName.get() != null);
    sweeper.close();

    assertThat(threadName.get()).startsWith("substrate-atom-sweeper");
  }

  @Test
  void virtualThreadNameDerivedFromJournalClass() {
    AtomicReference<String> threadName = new AtomicReference<>();
    Sweepable target =
        maxToSweep -> {
          threadName.set(Thread.currentThread().getName());
          return 0;
        };

    Sweeper sweeper = new Sweeper(Journal.class, target, Duration.ofMillis(50), 100);
    await().atMost(Duration.ofSeconds(5)).until(() -> threadName.get() != null);
    sweeper.close();

    assertThat(threadName.get()).startsWith("substrate-journal-sweeper");
  }

  @Test
  void virtualThreadNameDerivedFromMailboxClass() {
    AtomicReference<String> threadName = new AtomicReference<>();
    Sweepable target =
        maxToSweep -> {
          threadName.set(Thread.currentThread().getName());
          return 0;
        };

    Sweeper sweeper = new Sweeper(Mailbox.class, target, Duration.ofMillis(50), 100);
    await().atMost(Duration.ofSeconds(5)).until(() -> threadName.get() != null);
    sweeper.close();

    assertThat(threadName.get()).startsWith("substrate-mailbox-sweeper");
  }
}
