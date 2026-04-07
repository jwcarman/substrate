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
package org.jwcarman.substrate.memory;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryMailboxSpiTest {

  private static final String KEY = "substrate:mailbox:test";

  private InMemoryMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox = new InMemoryMailboxSpi();
  }

  @Test
  void deliverThenAwaitReturnsImmediately() {
    mailbox.deliver(KEY, "hello");

    CompletableFuture<String> future = mailbox.await(KEY, Duration.ofSeconds(1));

    assertTrue(future.isDone());
    assertEquals("hello", future.join());
  }

  @Test
  void awaitThenDeliverCompletesAsync() {
    CompletableFuture<String> future = mailbox.await(KEY, Duration.ofSeconds(5));

    assertFalse(future.isDone());

    mailbox.deliver(KEY, "world");

    await().atMost(Duration.ofSeconds(2)).until(future::isDone);
    assertEquals("world", future.join());
  }

  @Test
  void awaitTimesOut() {
    CompletableFuture<String> future = mailbox.await(KEY, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .until(
            () -> {
              if (!future.isDone()) return false;
              return future.isCompletedExceptionally();
            });

    assertTrue(future.isCompletedExceptionally());
    assertThrows(Exception.class, future::join);
  }

  @Test
  void deleteCancelsPendingFuture() {
    CompletableFuture<String> future = mailbox.await(KEY, Duration.ofSeconds(30));

    mailbox.delete(KEY);

    assertTrue(future.isCancelled());
    assertThrows(CancellationException.class, future::join);
  }

  @Test
  void deleteRemovesDeliveredValue() {
    mailbox.deliver(KEY, "hello");

    mailbox.delete(KEY);

    CompletableFuture<String> future = mailbox.await(KEY, Duration.ofMillis(50));

    await()
        .atMost(Duration.ofSeconds(2))
        .until(
            () -> {
              if (!future.isDone()) return false;
              return future.isCompletedExceptionally();
            });

    assertTrue(future.isCompletedExceptionally());
  }
}
