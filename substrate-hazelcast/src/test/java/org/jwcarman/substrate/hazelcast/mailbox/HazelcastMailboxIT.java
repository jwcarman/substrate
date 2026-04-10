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
package org.jwcarman.substrate.hazelcast.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.hazelcast.AbstractHazelcastIT;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

class HazelcastMailboxIT extends AbstractHazelcastIT {

  private HazelcastMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox =
        new HazelcastMailboxSpi(
            hazelcast, "substrate:mailbox:", "substrate-mailbox-" + System.nanoTime());
  }

  @Test
  void deliverThenGetReturnsValue() {
    String key = mailbox.mailboxKey("test-" + System.nanoTime());

    mailbox.create(key, Duration.ofMinutes(5));
    mailbox.deliver(key, "hello".getBytes(StandardCharsets.UTF_8));

    Optional<byte[]> result = mailbox.get(key);
    assertThat(result).isPresent();
    assertThat(new String(result.get(), StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void getThrowsWhenMailboxDoesNotExist() {
    String key = mailbox.mailboxKey("absent-" + System.nanoTime());

    assertThrows(MailboxExpiredException.class, () -> mailbox.get(key));
  }

  @Test
  void getReturnsEmptyWhenCreatedButNotDelivered() {
    String key = mailbox.mailboxKey("created-" + System.nanoTime());
    mailbox.create(key, Duration.ofMinutes(5));

    Optional<byte[]> result = mailbox.get(key);

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesValue() {
    String key = mailbox.mailboxKey("delete-" + System.nanoTime());

    mailbox.create(key, Duration.ofMinutes(5));
    mailbox.deliver(key, "to-delete".getBytes(StandardCharsets.UTF_8));
    mailbox.delete(key);

    assertThrows(MailboxExpiredException.class, () -> mailbox.get(key));
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }

  @Test
  void secondDeliveryThrowsMailboxFullException() {
    String key = mailbox.mailboxKey("double-" + System.nanoTime());
    mailbox.create(key, Duration.ofMinutes(5));
    mailbox.deliver(key, "first".getBytes(StandardCharsets.UTF_8));

    assertThrows(
        MailboxFullException.class,
        () -> mailbox.deliver(key, "second".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void originalValueSurvivesAfterMailboxFullException() {
    String key = mailbox.mailboxKey("survive-" + System.nanoTime());
    mailbox.create(key, Duration.ofMinutes(5));
    mailbox.deliver(key, "original".getBytes(StandardCharsets.UTF_8));

    assertThrows(
        MailboxFullException.class,
        () -> mailbox.deliver(key, "replacement".getBytes(StandardCharsets.UTF_8)));

    assertThat(mailbox.get(key)).isPresent();
    assertThat(new String(mailbox.get(key).get(), StandardCharsets.UTF_8)).isEqualTo("original");
  }

  @Test
  void concurrentDeliveryExactlyOneSucceeds() {
    String key = mailbox.mailboxKey("concurrent-" + System.nanoTime());
    mailbox.create(key, Duration.ofMinutes(5));

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
                            mailbox.deliver(key, ("payload-" + i).getBytes(StandardCharsets.UTF_8));
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

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threadCount - 1);
    assertThat(mailbox.get(key)).isPresent();
  }
}
