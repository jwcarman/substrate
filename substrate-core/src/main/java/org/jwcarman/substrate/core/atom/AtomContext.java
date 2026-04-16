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

import java.time.Duration;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;

/**
 * Shared infrastructure passed into every {@link DefaultAtom} instance produced by a {@link
 * DefaultAtomFactory}. Bundles dependencies that are factory-scoped (one per app) rather than
 * handle-scoped, keeping the {@code DefaultAtom} constructor signature small.
 *
 * @param spi the atom SPI used for backend operations
 * @param transformer the payload transformer applied to value bytes on write and read
 * @param notifier the notifier used to publish and subscribe to atom events
 * @param maxTtl the maximum TTL a caller may request when setting or touching an atom
 * @param shutdownCoordinator the coordinator used to register subscription cancellers for clean
 *     shutdown
 */
public record AtomContext(
    AtomSpi spi,
    PayloadTransformer transformer,
    Notifier notifier,
    Duration maxTtl,
    ShutdownCoordinator shutdownCoordinator) {}
