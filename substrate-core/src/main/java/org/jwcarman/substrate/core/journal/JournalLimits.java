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

/**
 * Bundles the configurable upper bounds a {@link DefaultJournalFactory} enforces on the journals it
 * creates and the {@link DefaultJournal} instances it constructs.
 *
 * @param subscriptionQueueCapacity the capacity of each subscription's bounded handoff queue
 * @param maxInactivityTtl the maximum inactivity TTL a caller may request when creating a journal
 * @param maxEntryTtl the maximum per-entry TTL a caller may request when appending
 * @param maxRetentionTtl the maximum retention TTL a caller may request when completing
 */
public record JournalLimits(
    int subscriptionQueueCapacity,
    Duration maxInactivityTtl,
    Duration maxEntryTtl,
    Duration maxRetentionTtl) {

  /** Default subscription queue capacity. */
  public static final int DEFAULT_SUBSCRIPTION_QUEUE_CAPACITY = 1024;

  /** Default inactivity TTL ceiling for newly created journals. */
  public static final Duration DEFAULT_MAX_INACTIVITY_TTL = Duration.ofHours(24);

  /** Default per-entry TTL ceiling for appended journal entries. */
  public static final Duration DEFAULT_MAX_ENTRY_TTL = Duration.ofDays(7);

  /** Default retention TTL ceiling for completed journals. */
  public static final Duration DEFAULT_MAX_RETENTION_TTL = Duration.ofDays(30);

  /**
   * Returns a {@code JournalLimits} populated with the canonical defaults — the same values {@link
   * org.jwcarman.substrate.core.autoconfigure.SubstrateProperties.JournalProperties} applies when
   * no explicit configuration is present.
   */
  public static JournalLimits defaults() {
    return new JournalLimits(
        DEFAULT_SUBSCRIPTION_QUEUE_CAPACITY,
        DEFAULT_MAX_INACTIVITY_TTL,
        DEFAULT_MAX_ENTRY_TTL,
        DEFAULT_MAX_RETENTION_TTL);
  }
}
