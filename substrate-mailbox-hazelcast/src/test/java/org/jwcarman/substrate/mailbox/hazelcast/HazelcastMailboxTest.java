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
package org.jwcarman.substrate.mailbox.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HazelcastMailboxTest {

  @Mock private HazelcastInstance hazelcastInstance;
  @Mock private IMap<String, byte[]> map;

  private HazelcastMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    when(hazelcastInstance.<String, byte[]>getMap("substrate-mailbox")).thenReturn(map);
    mailbox = new HazelcastMailboxSpi(hazelcastInstance, "substrate:mailbox:", "substrate-mailbox");
  }

  @Test
  void deliverReplacesValueInMap() {
    when(map.replace("test-key", "test-value".getBytes(StandardCharsets.UTF_8)))
        .thenReturn(new byte[0]);

    mailbox.deliver("test-key", "test-value".getBytes(StandardCharsets.UTF_8));

    verify(map).replace("test-key", "test-value".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getReturnsValueWhenPresent() {
    when(map.get("test-key")).thenReturn("existing-value".getBytes(StandardCharsets.UTF_8));

    Optional<byte[]> result = mailbox.get("test-key");

    assertThat(result).contains("existing-value".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getThrowsWhenAbsent() {
    when(map.get("test-key")).thenReturn(null);

    assertThrows(MailboxExpiredException.class, () -> mailbox.get("test-key"));
  }

  @Test
  void getReturnsEmptyWhenCreatedButNotDelivered() {
    when(map.get("test-key")).thenReturn(new byte[0]);

    Optional<byte[]> result = mailbox.get("test-key");

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesKeyFromMap() {
    mailbox.delete("test-key");

    verify(map).remove("test-key");
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-mailbox")).isEqualTo("substrate:mailbox:my-mailbox");
  }
}
