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

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.time.Duration;

public abstract class AbstractJournalSpi implements JournalSpi {

  private static final TimeBasedEpochGenerator UUID_GENERATOR =
      Generators.timeBasedEpochGenerator();

  private final String prefix;

  protected AbstractJournalSpi(String prefix) {
    this.prefix = prefix;
  }

  protected String prefix() {
    return prefix;
  }

  @Override
  public String journalKey(String name) {
    return prefix + name;
  }

  @Override
  public void create(String key, Duration inactivityTtl) {
    // Default no-op for backends that don't need explicit journal creation
  }

  @Override
  public int sweep(int maxToSweep) {
    return 0;
  }

  protected String generateEntryId() {
    return UUID_GENERATOR.generate().toString();
  }
}
