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
package org.jwcarman.substrate.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JournalSweeperTest {

  @Test
  void constructorRejectsBatchSizeZero() {
    JournalSpi spi = mock(JournalSpi.class);
    assertThatThrownBy(() -> new JournalSweeper(spi, Duration.ofMinutes(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNegativeBatchSize() {
    JournalSpi spi = mock(JournalSpi.class);
    assertThatThrownBy(() -> new JournalSweeper(spi, Duration.ofMinutes(1), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsZeroInterval() {
    JournalSpi spi = mock(JournalSpi.class);
    assertThatThrownBy(() -> new JournalSweeper(spi, Duration.ZERO, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNegativeInterval() {
    JournalSpi spi = mock(JournalSpi.class);
    assertThatThrownBy(() -> new JournalSweeper(spi, Duration.ofMinutes(-1), 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tickDrainsUntilBatchNotFull() {
    JournalSpi spi = mock(JournalSpi.class);
    when(spi.sweep(100)).thenReturn(100, 100, 50);

    JournalSweeper sweeper = new JournalSweeper(spi, Duration.ofMinutes(1), 100);
    sweeper.tick();
    sweeper.close();

    verify(spi, org.mockito.Mockito.times(3)).sweep(100);
  }

  @Test
  void tickStopsAtIterationCap() {
    JournalSpi spi = mock(JournalSpi.class);
    AtomicInteger callCount = new AtomicInteger();
    when(spi.sweep(100))
        .thenAnswer(
            inv -> {
              callCount.incrementAndGet();
              return 100;
            });

    JournalSweeper sweeper = new JournalSweeper(spi, Duration.ofMinutes(1), 100);
    sweeper.tick();
    sweeper.close();

    assertThat(callCount.get()).isEqualTo(JournalSweeper.MAX_ITERATIONS_PER_TICK);
  }

  @Test
  void tickSwallowsRuntimeException() {
    JournalSpi spi = mock(JournalSpi.class);
    doThrow(new RuntimeException("boom")).when(spi).sweep(100);

    JournalSweeper sweeper = new JournalSweeper(spi, Duration.ofMinutes(1), 100);
    sweeper.tick();

    doReturn(0).when(spi).sweep(100);
    sweeper.tick();
    sweeper.close();

    verify(spi, atLeast(2)).sweep(100);
  }

  @Test
  void closeShutdownsScheduler() {
    JournalSpi spi = mock(JournalSpi.class);
    JournalSweeper sweeper = new JournalSweeper(spi, Duration.ofMinutes(1), 100);
    sweeper.close();
  }
}
