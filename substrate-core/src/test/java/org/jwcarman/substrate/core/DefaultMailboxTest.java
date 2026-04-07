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
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.spi.MailboxSpi;

class DefaultMailboxTest {

  private static final String KEY = "substrate:mailbox:test";

  private MailboxSpi spi;
  private DefaultMailbox mailbox;

  @BeforeEach
  void setUp() {
    spi = mock(MailboxSpi.class);
    mailbox = new DefaultMailbox(spi, KEY);
  }

  @Test
  void keyReturnsTheBoundKey() {
    assertEquals(KEY, mailbox.key());
  }

  @Test
  void deliverDelegatesToSpiWithBoundKey() {
    mailbox.deliver("hello");

    verify(spi).deliver(KEY, "hello");
  }

  @Test
  void awaitDelegatesToSpiWithBoundKey() {
    Duration timeout = Duration.ofSeconds(5);
    CompletableFuture<String> expected = CompletableFuture.completedFuture("result");
    when(spi.await(KEY, timeout)).thenReturn(expected);

    CompletableFuture<String> result = mailbox.await(timeout);

    assertSame(expected, result);
    verify(spi).await(KEY, timeout);
  }

  @Test
  void deleteDelegatesToSpiWithBoundKey() {
    mailbox.delete();

    verify(spi).delete(KEY);
  }
}
