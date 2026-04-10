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
package org.jwcarman.substrate.core.memory.mailbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

class InMemoryMailboxSpiTest {

  private static final String KEY = "substrate:mailbox:test";

  private InMemoryMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox = new InMemoryMailboxSpi();
  }

  @Test
  void getOnCreatedButNotDeliveredMailboxReturnsEmpty() {
    mailbox.create(KEY, Duration.ofMinutes(5));

    Optional<byte[]> result = mailbox.get(KEY);

    assertTrue(result.isEmpty());
  }

  @Test
  void deliverThenGetReturnsValue() {
    mailbox.create(KEY, Duration.ofMinutes(5));
    mailbox.deliver(KEY, "hello".getBytes(UTF_8));

    Optional<byte[]> result = mailbox.get(KEY);

    assertTrue(result.isPresent());
    assertArrayEquals("hello".getBytes(UTF_8), result.get());
  }

  @Test
  void getOnNonExistentMailboxThrowsMailboxExpiredException() {
    assertThrows(MailboxExpiredException.class, () -> mailbox.get(KEY));
  }

  @Test
  void deleteRemovesMailbox() {
    mailbox.create(KEY, Duration.ofMinutes(5));
    mailbox.deliver(KEY, "hello".getBytes(UTF_8));

    mailbox.delete(KEY);

    assertThrows(MailboxExpiredException.class, () -> mailbox.get(KEY));
  }

  @Test
  void mailboxKeyAppliesPrefix() {
    assertEquals("substrate:mailbox:my-key", mailbox.mailboxKey("my-key"));
  }

  @Test
  void deliverThrowsAfterTtlExpires() {
    mailbox.create(KEY, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThrows(
                    MailboxExpiredException.class,
                    () -> mailbox.deliver(KEY, "late".getBytes(UTF_8))));
  }

  @Test
  void getThrowsAfterTtlExpires() {
    mailbox.create(KEY, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThrows(MailboxExpiredException.class, () -> mailbox.get(KEY)));
  }

  @Test
  void secondDeliveryThrowsMailboxFullException() {
    mailbox.create(KEY, Duration.ofMinutes(5));
    mailbox.deliver(KEY, "first".getBytes(UTF_8));

    assertThrows(MailboxFullException.class, () -> mailbox.deliver(KEY, "second".getBytes(UTF_8)));
  }

  @Test
  void originalValueSurvivesAfterMailboxFullException() {
    mailbox.create(KEY, Duration.ofMinutes(5));
    mailbox.deliver(KEY, "original".getBytes(UTF_8));

    assertThrows(
        MailboxFullException.class, () -> mailbox.deliver(KEY, "replacement".getBytes(UTF_8)));

    Optional<byte[]> result = mailbox.get(KEY);
    assertTrue(result.isPresent());
    assertArrayEquals("original".getBytes(UTF_8), result.get());
  }

  @Test
  void deliverThrowsExpiredOnDeadMailboxNotFull() {
    assertThrows(
        MailboxExpiredException.class, () -> mailbox.deliver(KEY, "value".getBytes(UTF_8)));
  }

  @Test
  void concurrentDeliveryExactlyOneSucceeds() {
    mailbox.create(KEY, Duration.ofMinutes(5));

    int threadCount = 8;
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    IntStream.range(0, threadCount)
        .mapToObj(
            i ->
                Thread.ofVirtual()
                    .start(
                        () -> {
                          try {
                            mailbox.deliver(KEY, ("payload-" + i).getBytes(UTF_8));
                            successes.incrementAndGet();
                          } catch (MailboxFullException _) {
                            failures.incrementAndGet();
                          }
                        }))
        .toList()
        .forEach(
            t -> {
              try {
                t.join();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    assertEquals(1, successes.get());
    assertEquals(threadCount - 1, failures.get());

    Optional<byte[]> result = mailbox.get(KEY);
    assertTrue(result.isPresent());
    String value = new String(result.get(), UTF_8);
    assertTrue(value.startsWith("payload-"));
  }

  @Test
  void sweepRemovesExpiredEntries() {
    for (int i = 0; i < 5; i++) {
      mailbox.create("key-" + i, Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              int removed = mailbox.sweep(10);
              assertThat(removed).isEqualTo(5);
            });

    for (int i = 0; i < 5; i++) {
      final int idx = i;
      assertThrows(MailboxExpiredException.class, () -> mailbox.get("key-" + idx));
    }
  }

  @Test
  void sweepRespectsMaxToSweep() {
    for (int i = 0; i < 10; i++) {
      mailbox.create("key-" + i, Duration.ofMillis(50));
    }

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(mailbox.sweep(3)).isEqualTo(3));

    assertThat(mailbox.sweep(10)).isEqualTo(7);
  }

  @Test
  void sweepReturnsZeroWhenNothingExpired() {
    mailbox.create("key", Duration.ofSeconds(10));

    assertThat(mailbox.sweep(1000)).isZero();
  }
}
