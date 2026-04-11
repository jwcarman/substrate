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
package org.jwcarman.substrate.redis.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisMailboxTest {

  @Mock private RedisCommands<String, String> commands;

  private RedisMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox = new RedisMailboxSpi(commands, "substrate:mailbox:");
  }

  @Test
  void deliverUsesLuaScript() {
    when(commands.eval(
            any(String.class),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            eq(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)))))
        .thenReturn(1L);

    mailbox.deliver("substrate:mailbox:test", "hello".getBytes(StandardCharsets.UTF_8));

    verify(commands)
        .eval(
            any(String.class),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            eq(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void deliverThrowsExpiredWhenKeyMissing() {
    when(commands.eval(any(String.class), eq(ScriptOutputType.INTEGER), any(String[].class), any()))
        .thenReturn(0L);

    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        MailboxExpiredException.class, () -> mailbox.deliver("substrate:mailbox:test", data));
  }

  @Test
  void deliverThrowsFullWhenAlreadyDelivered() {
    when(commands.eval(any(String.class), eq(ScriptOutputType.INTEGER), any(String[].class), any()))
        .thenReturn(-1L);

    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThrows(MailboxFullException.class, () -> mailbox.deliver("substrate:mailbox:test", data));
  }

  @Test
  void getReturnsValueWhenExists() {
    when(commands.get("substrate:mailbox:test"))
        .thenReturn(
            Base64.getEncoder().encodeToString("existing-value".getBytes(StandardCharsets.UTF_8)));

    Optional<byte[]> result = mailbox.get("substrate:mailbox:test");

    assertThat(result).contains("existing-value".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getThrowsWhenValueDoesNotExist() {
    when(commands.get("substrate:mailbox:test")).thenReturn(null);

    assertThrows(MailboxExpiredException.class, () -> mailbox.get("substrate:mailbox:test"));
  }

  @Test
  void getReturnsEmptyWhenCreatedButNotDelivered() {
    when(commands.get("substrate:mailbox:test")).thenReturn("");

    Optional<byte[]> result = mailbox.get("substrate:mailbox:test");

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesKey() {
    when(commands.del("substrate:mailbox:test")).thenReturn(1L);
    mailbox.delete("substrate:mailbox:test");

    verify(commands).del("substrate:mailbox:test");
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-mailbox")).isEqualTo("substrate:mailbox:my-mailbox");
  }
}
