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
package org.jwcarman.substrate.mailbox.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.spi.Notifier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisMailboxTest {

  @Mock private RedisCommands<String, String> commands;
  @Mock private Notifier notifier;

  private RedisMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox = new RedisMailboxSpi(commands, notifier, "substrate:mailbox:", Duration.ofMinutes(5));
  }

  @Test
  void deliverSetsValueWithTtlAndNotifies() {
    mailbox.deliver("substrate:mailbox:test", "hello".getBytes(StandardCharsets.UTF_8));

    verify(commands)
        .set(
            eq("substrate:mailbox:test"),
            eq(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))),
            any(SetArgs.class));
    verify(notifier).notify("substrate:mailbox:test", "substrate:mailbox:test");
  }

  @Test
  void awaitReturnsImmediatelyWhenValueExists() {
    when(commands.get("substrate:mailbox:test"))
        .thenReturn(
            Base64.getEncoder().encodeToString("existing-value".getBytes(StandardCharsets.UTF_8)));

    CompletableFuture<byte[]> future =
        mailbox.await("substrate:mailbox:test", Duration.ofSeconds(5));

    assertThat(future.join()).isEqualTo("existing-value".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void awaitReturnsPendingFutureWhenValueDoesNotExist() {
    when(commands.get("substrate:mailbox:test")).thenReturn(null);

    CompletableFuture<byte[]> future =
        mailbox.await("substrate:mailbox:test", Duration.ofSeconds(5));

    assertThat(future).isNotDone();
  }

  @Test
  void deleteRemovesKeyAndCancelsPendingFuture() {
    when(commands.get("substrate:mailbox:test")).thenReturn(null);
    CompletableFuture<byte[]> future =
        mailbox.await("substrate:mailbox:test", Duration.ofSeconds(30));

    when(commands.del("substrate:mailbox:test")).thenReturn(1L);
    mailbox.delete("substrate:mailbox:test");

    verify(commands).del("substrate:mailbox:test");
    assertThat(future).isCancelled();
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-mailbox")).isEqualTo("substrate:mailbox:my-mailbox");
  }
}
