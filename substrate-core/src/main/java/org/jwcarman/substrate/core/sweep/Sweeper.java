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

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class Sweeper implements AutoCloseable {

  private static final Log log = LogFactory.getLog(Sweeper.class);
  static final int MAX_ITERATIONS_PER_TICK = 100;

  private final Class<?> primitiveType;
  private final Sweepable target;
  private final int batchSize;
  private final ScheduledExecutorService scheduler;

  public Sweeper(Class<?> primitiveType, Sweepable target, Duration interval, int batchSize) {
    if (primitiveType == null) {
      throw new IllegalArgumentException("primitiveType must not be null");
    }
    if (target == null) {
      throw new IllegalArgumentException("target must not be null");
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
    }
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive: " + interval);
    }
    this.primitiveType = primitiveType;
    this.target = target;
    this.batchSize = batchSize;

    String threadName = "substrate-" + primitiveType.getSimpleName().toLowerCase() + "-sweeper";
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name(threadName, 0).factory());

    long intervalMs = interval.toMillis();
    long jitterMs = ThreadLocalRandom.current().nextLong(intervalMs / 2 + 1);
    scheduler.scheduleWithFixedDelay(
        this::tick, intervalMs + jitterMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  void tick() {
    try {
      int totalDeleted = 0;
      for (int i = 0; i < MAX_ITERATIONS_PER_TICK; i++) {
        int deleted = target.sweep(batchSize);
        totalDeleted += deleted;
        if (deleted < batchSize) {
          break;
        }
      }
      if (totalDeleted > 0 && log.isDebugEnabled()) {
        log.debug(
            "Swept " + totalDeleted + " expired " + primitiveType.getSimpleName() + " records");
      }
    } catch (RuntimeException e) {
      log.warn(
          "Sweep failed for " + primitiveType.getSimpleName() + "; will retry on next tick", e);
    }
  }

  @Override
  public void close() {
    scheduler.shutdown();
  }
}
