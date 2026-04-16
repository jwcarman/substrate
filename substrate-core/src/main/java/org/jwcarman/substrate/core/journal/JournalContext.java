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

import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;

/**
 * Shared infrastructure passed into every {@link DefaultJournal} instance produced by a {@link
 * DefaultJournalFactory}. Bundles dependencies that are factory-scoped (one per app) rather than
 * handle-scoped, keeping the {@code DefaultJournal} constructor signature small.
 *
 * @param spi the journal SPI used for backend operations
 * @param transformer the payload transformer applied to entry bytes on write and read
 * @param notifier the notifier used to publish and subscribe to journal events
 * @param limits the configurable upper bounds enforced on subscriptions and TTL inputs
 * @param shutdownCoordinator the coordinator used to register subscription cancellers for clean
 *     shutdown
 */
public record JournalContext(
    JournalSpi spi,
    PayloadTransformer transformer,
    Notifier notifier,
    JournalLimits limits,
    ShutdownCoordinator shutdownCoordinator) {}
