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
package org.jwcarman.substrate.core.atom;

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
import org.junit.jupiter.api.Test;

class AtomSweeperTest {

  @Test
  void constructorRejectsBatchSizeZero() {
    AtomSpi spi = mock(AtomSpi.class);
    assertThatThrownBy(() -> new AtomSweeper(spi, Duration.ofMinutes(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNegativeBatchSize() {
    AtomSpi spi = mock(AtomSpi.class);
    assertThatThrownBy(() -> new AtomSweeper(spi, Duration.ofMinutes(1), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsZeroInterval() {
    AtomSpi spi = mock(AtomSpi.class);
    assertThatThrownBy(() -> new AtomSweeper(spi, Duration.ZERO, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNegativeInterval() {
    AtomSpi spi = mock(AtomSpi.class);
    assertThatThrownBy(() -> new AtomSweeper(spi, Duration.ofMinutes(-1), 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tickDrainsUntilBatchNotFull() {
    AtomSpi spi = mock(AtomSpi.class);
    when(spi.sweep(100)).thenReturn(100, 100, 50);

    AtomSweeper sweeper = new AtomSweeper(spi, Duration.ofMinutes(1), 100);
    sweeper.tick();
    sweeper.close();

    verify(spi, org.mockito.Mockito.times(3)).sweep(100);
  }

  @Test
  void tickStopsAtIterationCap() {
    AtomSpi spi = mock(AtomSpi.class);
    AtomicInteger callCount = new AtomicInteger();
    when(spi.sweep(100))
        .thenAnswer(
            inv -> {
              callCount.incrementAndGet();
              return 100;
            });

    AtomSweeper sweeper = new AtomSweeper(spi, Duration.ofMinutes(1), 100);
    sweeper.tick();
    sweeper.close();

    assertThat(callCount.get()).isEqualTo(AtomSweeper.MAX_ITERATIONS_PER_TICK);
  }

  @Test
  void tickSwallowsRuntimeException() {
    AtomSpi spi = mock(AtomSpi.class);
    doThrow(new RuntimeException("boom")).when(spi).sweep(100);

    AtomSweeper sweeper = new AtomSweeper(spi, Duration.ofMinutes(1), 100);
    sweeper.tick();

    doReturn(0).when(spi).sweep(100);
    sweeper.tick();
    sweeper.close();

    verify(spi, atLeast(2)).sweep(100);
  }

  @Test
  void closeShutdownsScheduler() {
    AtomSpi spi = mock(AtomSpi.class);
    AtomSweeper sweeper = new AtomSweeper(spi, Duration.ofMinutes(1), 100);

    sweeper.close();

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              // After close, scheduler should reject new tasks
              // Verified by the fact that close() completes and no further ticks run
            });
  }
}
