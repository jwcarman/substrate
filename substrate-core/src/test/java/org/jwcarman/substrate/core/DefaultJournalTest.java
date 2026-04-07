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
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.JournalEntry;
import org.jwcarman.substrate.spi.JournalSpi;

class DefaultJournalTest {

  private static final String KEY = "substrate:journal:test";

  private JournalSpi spi;
  private DefaultJournal journal;

  @BeforeEach
  void setUp() {
    spi = mock(JournalSpi.class);
    journal = new DefaultJournal(spi, KEY);
  }

  @Test
  void keyReturnsTheBoundKey() {
    assertEquals(KEY, journal.key());
  }

  @Test
  void appendDelegatesToSpiWithBoundKey() {
    when(spi.append(KEY, "data")).thenReturn("entry-1");

    String id = journal.append("data");

    assertEquals("entry-1", id);
    verify(spi).append(KEY, "data");
  }

  @Test
  void appendWithTtlDelegatesToSpiWithBoundKey() {
    Duration ttl = Duration.ofMinutes(5);
    when(spi.append(KEY, "data", ttl)).thenReturn("entry-2");

    String id = journal.append("data", ttl);

    assertEquals("entry-2", id);
    verify(spi).append(KEY, "data", ttl);
  }

  @Test
  void readAfterDelegatesToSpiWithBoundKey() {
    JournalEntry entry = new JournalEntry("1", KEY, "data", Instant.now());
    when(spi.readAfter(KEY, "0")).thenReturn(Stream.of(entry));

    var entries = journal.readAfter("0").toList();

    assertEquals(1, entries.size());
    assertEquals(entry, entries.getFirst());
    verify(spi).readAfter(KEY, "0");
  }

  @Test
  void readLastDelegatesToSpiWithBoundKey() {
    JournalEntry entry = new JournalEntry("1", KEY, "data", Instant.now());
    when(spi.readLast(KEY, 5)).thenReturn(Stream.of(entry));

    var entries = journal.readLast(5).toList();

    assertEquals(1, entries.size());
    verify(spi).readLast(KEY, 5);
  }

  @Test
  void completeDelegatesToSpiWithBoundKey() {
    journal.complete();

    verify(spi).complete(KEY);
  }

  @Test
  void deleteDelegatesToSpiWithBoundKey() {
    journal.delete();

    verify(spi).delete(KEY);
  }
}
