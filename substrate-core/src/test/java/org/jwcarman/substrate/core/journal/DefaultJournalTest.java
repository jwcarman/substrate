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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.SubscriberConfig;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.jwcarman.substrate.journal.JournalEntry;
import org.jwcarman.substrate.journal.JournalNotFoundException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultJournalTest {

  private static final String KEY = "substrate:journal:test";

  @Mock private JournalSpi spi;
  @Mock private Codec<String> codec;
  @Mock private Notifier notifier;

  private final ShutdownCoordinator coordinator = new ShutdownCoordinator();
  private DefaultJournal<String> journal;

  @BeforeEach
  void setUp() {
    lenient()
        .when(codec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));
    lenient()
        .when(codec.decode(any(byte[].class)))
        .thenAnswer(inv -> new String((byte[]) inv.getArgument(0), UTF_8));
    lenient().when(notifier.subscribeToJournal(anyString(), any())).thenReturn(() -> {});
    journal =
        new DefaultJournal<>(
            spi,
            KEY,
            codec,
            PayloadTransformer.IDENTITY,
            notifier,
            JournalLimits.defaults(),
            coordinator);
  }

  private DefaultJournal<String> connectedJournal() {
    return new DefaultJournal<>(
        spi,
        KEY,
        codec,
        PayloadTransformer.IDENTITY,
        notifier,
        JournalLimits.defaults(),
        coordinator,
        true);
  }

  @Test
  void connectSourcedSubscribeOnNonexistentThrowsJournalNotFoundException() {
    when(spi.exists(KEY)).thenReturn(false);
    DefaultJournal<String> j = connectedJournal();
    assertThrows(JournalNotFoundException.class, j::subscribe);
  }

  @Test
  void connectSourcedAppendOnNonexistentThrows() {
    when(spi.exists(KEY)).thenReturn(false);
    DefaultJournal<String> j = connectedJournal();
    assertThrows(JournalNotFoundException.class, () -> j.append("x", Duration.ofMinutes(1)));
  }

  @Test
  void createSourcedHandleDoesNotProbe() {
    when(spi.readLast(KEY, 1)).thenReturn(List.of());
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      verify(spi, never()).exists(anyString());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void probeFiresExactlyOnceAcrossManyOperations() {
    when(spi.exists(KEY)).thenReturn(true);
    when(spi.readLast(KEY, 1)).thenReturn(List.of());
    when(spi.append(eq(KEY), any(), any())).thenReturn("entry-1");
    DefaultJournal<String> j = connectedJournal();

    BlockingSubscription<JournalEntry<String>> s1 = j.subscribe();
    try {
      j.append("x", Duration.ofMinutes(1));
      BlockingSubscription<JournalEntry<String>> s2 = j.subscribe();
      s2.cancel();
    } finally {
      s1.cancel();
    }
    verify(spi, times(1)).exists(KEY);
  }

  @Test
  void connectSourcedDeleteDoesNotProbe() {
    DefaultJournal<String> j = connectedJournal();
    j.delete();
    verify(spi, never()).exists(anyString());
    verify(spi).delete(KEY);
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
    verify(notifier).notifyJournalChanged(KEY);
  }

  @Test
  void appendThrowsWhenEntryTtlExceedsMax() {
    Duration excessiveEntryTtl = Duration.ofDays(30);
    assertThrows(IllegalArgumentException.class, () -> journal.append("data", excessiveEntryTtl));
  }

  @Test
  void completeDelegatesToSpiWithBoundKey() {
    Duration retentionTtl = Duration.ofDays(1);
    journal.complete(retentionTtl);

    verify(spi).complete(KEY, retentionTtl);
    verify(notifier).notifyJournalCompleted(KEY);
  }

  @Test
  void completeThrowsWhenRetentionTtlExceedsMax() {
    Duration excessiveRetentionTtl = Duration.ofDays(60);
    assertThrows(IllegalArgumentException.class, () -> journal.complete(excessiveRetentionTtl));
  }

  @Test
  void deleteDelegatesToSpiAndNotifiesDeleted() {
    journal.delete();

    verify(spi).delete(KEY);
    verify(notifier).notifyJournalDeleted(KEY);
  }

  @Test
  void subscribeReturnsActiveSubscription() {
    when(spi.readLast(KEY, 1)).thenReturn(List.of());

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeAfterReturnsActiveSubscription() {
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeAfter("entry-1");
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeLastReturnsActiveSubscription() {
    when(spi.readLast(KEY, 5)).thenReturn(List.of());

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeLast(5);
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeLastWithPreloadUsesLastIdAsCheckpoint() {
    var raw =
        new RawJournalEntry("entry-3", KEY, "preloaded".getBytes(UTF_8), java.time.Instant.now());
    when(spi.readLast(KEY, 10)).thenReturn(List.of(raw));

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeLast(10);
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeCallbackReturnsActiveSubscription() {
    when(spi.readLast(KEY, 1)).thenReturn(List.of());

    Subscription sub = journal.subscribe((JournalEntry<String> value) -> {});
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeCallbackWithCustomizerReturnsActiveSubscription() {
    when(spi.readLast(KEY, 1)).thenReturn(List.of());

    Subscription sub =
        journal.subscribe((SubscriberConfig<JournalEntry<String>> cfg) -> cfg.onNext(value -> {}));
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeAfterCallbackReturnsActiveSubscription() {
    Subscription sub = journal.subscribeAfter("entry-1", (JournalEntry<String> value) -> {});
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeAfterCallbackWithCustomizerReturnsActiveSubscription() {
    Subscription sub =
        journal.subscribeAfter(
            "entry-1", (SubscriberConfig<JournalEntry<String>> cfg) -> cfg.onNext(value -> {}));
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeLastCallbackReturnsActiveSubscription() {
    when(spi.readLast(KEY, 3)).thenReturn(List.of());

    Subscription sub = journal.subscribeLast(3, (JournalEntry<String> value) -> {});
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void subscribeLastCallbackWithCustomizerReturnsActiveSubscription() {
    when(spi.readLast(KEY, 3)).thenReturn(List.of());

    Subscription sub =
        journal.subscribeLast(
            3, (SubscriberConfig<JournalEntry<String>> cfg) -> cfg.onNext(value -> {}));
    try {
      assertNotNull(sub);
      assertTrue(sub.isActive());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void feederPushesPreloadedEntriesBeforePolling() {
    var raw1 =
        new RawJournalEntry("entry-1", KEY, "preload1".getBytes(UTF_8), java.time.Instant.now());
    var raw2 =
        new RawJournalEntry("entry-2", KEY, "preload2".getBytes(UTF_8), java.time.Instant.now());
    when(spi.readLast(KEY, 2)).thenReturn(List.of(raw1, raw2));
    lenient().when(spi.readAfter(eq(KEY), anyString())).thenReturn(List.of());
    lenient().when(spi.isComplete(KEY)).thenReturn(false);

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeLast(2);
    try {
      var result1 = sub.next(java.time.Duration.ofSeconds(5));
      assertInstanceOf(org.jwcarman.substrate.NextResult.Value.class, result1);
      assertEquals(
          "preload1",
          ((org.jwcarman.substrate.NextResult.Value<JournalEntry<String>>) result1).value().data());

      var result2 = sub.next(java.time.Duration.ofSeconds(5));
      assertInstanceOf(org.jwcarman.substrate.NextResult.Value.class, result2);
      assertEquals(
          "preload2",
          ((org.jwcarman.substrate.NextResult.Value<JournalEntry<String>>) result2).value().data());
    } finally {
      sub.cancel();
    }
  }

  @Test
  void feederMarksExpiredWhenJournalExpires() {
    when(spi.readLast(KEY, 1)).thenReturn(List.of());
    when(spi.readLast(KEY, Integer.MAX_VALUE))
        .thenThrow(new org.jwcarman.substrate.journal.JournalExpiredException(KEY));

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      var result = sub.next(java.time.Duration.ofSeconds(5));
      assertInstanceOf(org.jwcarman.substrate.NextResult.Expired.class, result);
    } finally {
      sub.cancel();
    }
  }

  @Test
  void feederMarksCompletedWhenJournalCompletes() {
    when(spi.readLast(KEY, 1)).thenReturn(List.of());
    when(spi.readLast(KEY, Integer.MAX_VALUE)).thenReturn(List.of());
    lenient().when(spi.readAfter(eq(KEY), anyString())).thenReturn(List.of());
    when(spi.isComplete(KEY)).thenReturn(true);

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      var result = sub.next(java.time.Duration.ofSeconds(5));
      assertInstanceOf(org.jwcarman.substrate.NextResult.Completed.class, result);
    } finally {
      sub.cancel();
    }
  }

  @Test
  void feederDrainsRemainingEntriesBeforeCompletionMarker() {
    var initial =
        new RawJournalEntry("entry-1", KEY, "first".getBytes(UTF_8), java.time.Instant.now());
    when(spi.readLast(KEY, 1)).thenReturn(List.of(initial));
    var late = new RawJournalEntry("entry-2", KEY, "late".getBytes(UTF_8), java.time.Instant.now());
    when(spi.readAfter(KEY, "entry-1")).thenReturn(List.of(late));
    when(spi.readAfter(KEY, "entry-2")).thenReturn(List.of());
    when(spi.isComplete(KEY)).thenReturn(true);

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      var result1 = sub.next(java.time.Duration.ofSeconds(5));
      assertInstanceOf(org.jwcarman.substrate.NextResult.Value.class, result1);
      assertEquals(
          "late",
          ((org.jwcarman.substrate.NextResult.Value<JournalEntry<String>>) result1).value().data());

      var result2 = sub.next(java.time.Duration.ofSeconds(5));
      assertInstanceOf(org.jwcarman.substrate.NextResult.Completed.class, result2);
    } finally {
      sub.cancel();
    }
  }

  @Test
  void feederHandlesExpirationDuringDrain() {
    var initial =
        new RawJournalEntry("entry-1", KEY, "data".getBytes(UTF_8), java.time.Instant.now());
    when(spi.readLast(KEY, 1)).thenReturn(List.of(initial));
    when(spi.readAfter(KEY, "entry-1"))
        .thenReturn(List.of())
        .thenThrow(new org.jwcarman.substrate.journal.JournalExpiredException(KEY));
    when(spi.isComplete(KEY)).thenReturn(true);

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribe();
    try {
      var result = sub.next(java.time.Duration.ofSeconds(5));
      assertInstanceOf(org.jwcarman.substrate.NextResult.Expired.class, result);
    } finally {
      sub.cancel();
    }
  }
}
