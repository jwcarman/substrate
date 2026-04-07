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
package org.jwcarman.substrate.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;

class JournalFactoryTest {

  @Test
  void createReturnsBoundJournalWithPrefixedKey() {
    InMemoryJournalSpi spi = new InMemoryJournalSpi();
    JournalFactory factory = new JournalFactory(spi);

    Journal journal = factory.create("my-stream");

    assertEquals("substrate:journal:my-stream", journal.key());
  }

  @Test
  void createdJournalDelegatesToSpi() {
    InMemoryJournalSpi spi = new InMemoryJournalSpi();
    JournalFactory factory = new JournalFactory(spi);

    Journal journal = factory.create("test");
    String id = journal.append("hello");

    assertNotNull(id);
    assertEquals(1, journal.readAfter("0-0").toList().size());
  }
}
