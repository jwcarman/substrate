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
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.memory.InMemoryMailboxSpi;
import org.jwcarman.substrate.memory.InMemoryNotifier;

class DefaultMailboxTest {

  private static final String KEY = "substrate:mailbox:test";

  private static final Codec<String> STRING_CODEC =
      new Codec<>() {
        @Override
        public byte[] encode(String value) {
          return value.getBytes(UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
          return new String(bytes, UTF_8);
        }
      };

  private InMemoryMailboxSpi spi;
  private InMemoryNotifier notifier;
  private DefaultMailbox<String> mailbox;

  @BeforeEach
  void setUp() {
    spi = new InMemoryMailboxSpi();
    notifier = new InMemoryNotifier();
    mailbox = new DefaultMailbox<>(spi, KEY, STRING_CODEC, notifier);
  }

  @Test
  void keyReturnsTheBoundKey() {
    assertEquals(KEY, mailbox.key());
  }

  @Test
  void deliverStoresValueAndNotifies() {
    mailbox.deliver("hello");

    assertTrue(spi.get(KEY).isPresent());
    assertArrayEquals("hello".getBytes(UTF_8), spi.get(KEY).get());
  }

  @Test
  void awaitReturnsImmediatelyIfAlreadyDelivered() {
    mailbox.deliver("hello");

    CompletableFuture<String> result = mailbox.await(Duration.ofSeconds(1));

    assertTrue(result.isDone());
    assertEquals("hello", result.join());
  }

  @Test
  void awaitCompletesWhenValueDeliveredLater() {
    CompletableFuture<String> result = mailbox.await(Duration.ofSeconds(5));

    assertFalse(result.isDone());

    mailbox.deliver("world");

    await().atMost(Duration.ofSeconds(2)).until(result::isDone);
    assertEquals("world", result.join());
  }

  @Test
  void awaitTimesOut() {
    CompletableFuture<String> result = mailbox.await(Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .until(
            () -> {
              if (!result.isDone()) return false;
              return result.isCompletedExceptionally();
            });

    assertTrue(result.isCompletedExceptionally());
    assertInstanceOf(
        TimeoutException.class, assertThrows(Exception.class, result::join).getCause());
  }

  @Test
  void deleteDelegatesToSpi() {
    mailbox.deliver("hello");

    mailbox.delete();

    assertTrue(spi.get(KEY).isEmpty());
  }
}
