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

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class JournalSweeper implements AutoCloseable {

  private static final Log log = LogFactory.getLog(JournalSweeper.class);
  static final int MAX_ITERATIONS_PER_TICK = 100;

  private final JournalSpi spi;
  private final int batchSize;
  private final ScheduledExecutorService scheduler;

  public JournalSweeper(JournalSpi spi, Duration interval, int batchSize) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
    }
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive: " + interval);
    }
    this.spi = spi;
    this.batchSize = batchSize;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("substrate-journal-sweeper", 0).factory());

    long intervalMs = interval.toMillis();
    long jitterMs = ThreadLocalRandom.current().nextLong(intervalMs / 2 + 1);
    scheduler.scheduleWithFixedDelay(
        this::tick, intervalMs + jitterMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  void tick() {
    try {
      int totalDeleted = 0;
      for (int i = 0; i < MAX_ITERATIONS_PER_TICK; i++) {
        int deleted = spi.sweep(batchSize);
        totalDeleted += deleted;
        if (deleted < batchSize) {
          break;
        }
      }
      if (totalDeleted > 0 && log.isDebugEnabled()) {
        log.debug("Swept " + totalDeleted + " expired journal entries");
      }
    } catch (RuntimeException e) {
      log.warn("Journal sweep failed; will retry on next tick", e);
    }
  }

  @Override
  public void close() {
    scheduler.shutdown();
  }
}
