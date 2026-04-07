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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.spi.JournalEntry;
import org.jwcarman.substrate.spi.JournalSpi;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultJournalTest {

  private static final String KEY = "substrate:journal:test";

  @Mock private JournalSpi spi;
  @Mock private Codec<String> codec;
  private DefaultJournal<String> journal;

  @BeforeEach
  void setUp() {
    lenient()
        .when(codec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));
    lenient()
        .when(codec.decode(any(byte[].class)))
        .thenAnswer(inv -> new String((byte[]) inv.getArgument(0), UTF_8));
    journal = new DefaultJournal<>(spi, KEY, codec);
  }

  @Test
  void keyReturnsTheBoundKey() {
    assertEquals(KEY, journal.key());
  }

  @Test
  void appendDelegatesToSpiWithBoundKey() {
    when(spi.append(KEY, "data".getBytes(UTF_8))).thenReturn("entry-1");

    String id = journal.append("data");

    assertEquals("entry-1", id);
    verify(spi).append(KEY, "data".getBytes(UTF_8));
  }

  @Test
  void appendWithTtlDelegatesToSpiWithBoundKey() {
    Duration ttl = Duration.ofMinutes(5);
    when(spi.append(KEY, "data".getBytes(UTF_8), ttl)).thenReturn("entry-2");

    String id = journal.append("data", ttl);

    assertEquals("entry-2", id);
    verify(spi).append(KEY, "data".getBytes(UTF_8), ttl);
  }

  @Test
  void readAfterDelegatesToSpiWithBoundKey() {
    JournalEntry entry = new JournalEntry("1", KEY, "data".getBytes(UTF_8), Instant.now());
    when(spi.readAfter(KEY, "0")).thenReturn(Stream.of(entry));

    var entries = journal.readAfter("0").toList();

    assertEquals(1, entries.size());
    assertEquals("data", entries.getFirst().data());
    verify(spi).readAfter(KEY, "0");
  }

  @Test
  void readLastDelegatesToSpiWithBoundKey() {
    JournalEntry entry = new JournalEntry("1", KEY, "data".getBytes(UTF_8), Instant.now());
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
