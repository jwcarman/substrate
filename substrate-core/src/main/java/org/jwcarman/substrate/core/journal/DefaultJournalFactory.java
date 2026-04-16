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
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.jwcarman.substrate.journal.Journal;
import org.jwcarman.substrate.journal.JournalFactory;

public class DefaultJournalFactory implements JournalFactory {

  private final CodecFactory codecFactory;
  private final JournalContext context;

  public DefaultJournalFactory(
      JournalSpi journalSpi,
      CodecFactory codecFactory,
      PayloadTransformer transformer,
      Notifier notifier,
      JournalLimits limits,
      ShutdownCoordinator shutdownCoordinator) {
    this.codecFactory = codecFactory;
    this.context =
        new JournalContext(journalSpi, transformer, notifier, limits, shutdownCoordinator);
  }

  @Override
  public <T> Journal<T> create(String name, Class<T> type, Duration inactivityTtl) {
    validateInactivityTtl(inactivityTtl);
    String key = context.spi().journalKey(name);
    context.spi().create(key, inactivityTtl);
    return new DefaultJournal<>(context, key, codecFactory.create(type), false);
  }

  @Override
  public <T> Journal<T> create(String name, TypeRef<T> typeRef, Duration inactivityTtl) {
    validateInactivityTtl(inactivityTtl);
    String key = context.spi().journalKey(name);
    context.spi().create(key, inactivityTtl);
    return new DefaultJournal<>(context, key, codecFactory.create(typeRef), false);
  }

  @Override
  public <T> Journal<T> connect(String name, Class<T> type) {
    String key = context.spi().journalKey(name);
    return new DefaultJournal<>(context, key, codecFactory.create(type), true);
  }

  @Override
  public <T> Journal<T> connect(String name, TypeRef<T> typeRef) {
    String key = context.spi().journalKey(name);
    return new DefaultJournal<>(context, key, codecFactory.create(typeRef), true);
  }

  private void validateInactivityTtl(Duration inactivityTtl) {
    if (inactivityTtl.compareTo(context.limits().maxInactivityTtl()) > 0) {
      throw new IllegalArgumentException(
          "Journal inactivity TTL "
              + inactivityTtl
              + " exceeds configured maximum "
              + context.limits().maxInactivityTtl());
    }
  }
}
