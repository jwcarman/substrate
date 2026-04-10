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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.journal.JournalCursor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultJournalTest {

  private static final String KEY = "substrate:journal:test";

  @Mock private JournalSpi spi;
  @Mock private Codec<String> codec;
  @Mock private NotifierSpi notifier;
  private DefaultJournal<String> journal;

  @BeforeEach
  void setUp() {
    lenient()
        .when(codec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));
    lenient()
        .when(codec.decode(any(byte[].class)))
        .thenAnswer(inv -> new String((byte[]) inv.getArgument(0), UTF_8));
    lenient().when(notifier.subscribe(any())).thenReturn(() -> {});
    journal =
        new DefaultJournal<>(spi, KEY, codec, notifier, Duration.ofDays(7), Duration.ofDays(30));
  }

  @Test
  void keyReturnsTheBoundKey() {
    assertEquals(KEY, journal.key());
  }

  @Test
  void appendWithTtlDelegatesToSpiWithBoundKey() {
    Duration ttl = Duration.ofMinutes(5);
    when(spi.append(KEY, "data".getBytes(UTF_8), ttl)).thenReturn("entry-2");

    String id = journal.append("data", ttl);

    assertEquals("entry-2", id);
    verify(spi).append(KEY, "data".getBytes(UTF_8), ttl);
    verify(notifier).notify(KEY, "entry-2");
  }

  @Test
  void appendThrowsWhenEntryTtlExceedsMax() {
    assertThrows(IllegalArgumentException.class, () -> journal.append("data", Duration.ofDays(30)));
  }

  @Test
  void completeDelegatesToSpiWithBoundKey() {
    Duration retentionTtl = Duration.ofDays(1);
    journal.complete(retentionTtl);

    verify(spi).complete(KEY, retentionTtl);
    verify(notifier).notify(KEY, "__COMPLETED__");
  }

  @Test
  void completeThrowsWhenRetentionTtlExceedsMax() {
    assertThrows(IllegalArgumentException.class, () -> journal.complete(Duration.ofDays(60)));
  }

  @Test
  void deleteDelegatesToSpiWithBoundKey() {
    journal.delete();

    verify(spi).delete(KEY);
  }

  @Test
  void readReturnsCursorFromTail() {
    when(spi.readLast(KEY, 1)).thenReturn(List.of());

    JournalCursor<String> cursor = journal.read();

    assertNotNull(cursor);
    assertTrue(cursor.isOpen());
    cursor.close();
  }

  @Test
  void readAfterReturnsCursor() {
    JournalCursor<String> cursor = journal.readAfter("entry-1");

    assertNotNull(cursor);
    assertTrue(cursor.isOpen());
    cursor.close();
  }
}
