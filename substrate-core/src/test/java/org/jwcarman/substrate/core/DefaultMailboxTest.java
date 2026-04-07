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
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.spi.MailboxSpi;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultMailboxTest {

  private static final String KEY = "substrate:mailbox:test";

  @Mock private MailboxSpi spi;
  @Mock private Codec<String> codec;
  private DefaultMailbox<String> mailbox;

  @BeforeEach
  void setUp() {
    lenient()
        .when(codec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));
    lenient()
        .when(codec.decode(any(byte[].class)))
        .thenAnswer(inv -> new String((byte[]) inv.getArgument(0), UTF_8));
    mailbox = new DefaultMailbox<>(spi, KEY, codec);
  }

  @Test
  void keyReturnsTheBoundKey() {
    assertEquals(KEY, mailbox.key());
  }

  @Test
  void deliverDelegatesToSpiWithBoundKey() {
    mailbox.deliver("hello");

    verify(spi).deliver(KEY, "hello".getBytes(UTF_8));
  }

  @Test
  void awaitDelegatesToSpiWithBoundKey() {
    Duration timeout = Duration.ofSeconds(5);
    CompletableFuture<byte[]> spiFuture =
        CompletableFuture.completedFuture("result".getBytes(UTF_8));
    when(spi.await(KEY, timeout)).thenReturn(spiFuture);

    CompletableFuture<String> result = mailbox.await(timeout);

    assertEquals("result", result.join());
    verify(spi).await(KEY, timeout);
  }

  @Test
  void deleteDelegatesToSpiWithBoundKey() {
    mailbox.delete();

    verify(spi).delete(KEY);
  }
}
