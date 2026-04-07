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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.spi.Notifier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HazelcastMailboxTest {

  @Mock private HazelcastInstance hazelcastInstance;
  @Mock private IMap<String, String> map;
  @Mock private Notifier notifier;

  private HazelcastMailbox mailbox;

  @BeforeEach
  void setUp() {
    when(hazelcastInstance.<String, String>getMap("substrate-mailbox")).thenReturn(map);
    mailbox =
        new HazelcastMailbox(
            hazelcastInstance,
            notifier,
            "substrate:mailbox:",
            "substrate-mailbox",
            Duration.ofMinutes(5));
  }

  @Test
  void deliverPutsValueInMapWithTtlAndNotifies() {
    mailbox.deliver("test-key", "test-value");

    verify(map)
        .put("test-key", "test-value", Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
    verify(notifier).notify("test-key", "test-value");
  }

  @Test
  void awaitReturnsImmediatelyIfValueExists() throws Exception {
    when(map.get("test-key")).thenReturn("existing-value");

    CompletableFuture<String> future = mailbox.await("test-key", Duration.ofSeconds(5));

    assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("existing-value");
  }

  @Test
  void awaitRegistersEntryListenerWhenValueAbsent() {
    when(map.get("test-key")).thenReturn(null);
    when(map.addEntryListener(any(), eq("test-key"), anyBoolean())).thenReturn(UUID.randomUUID());

    mailbox.await("test-key", Duration.ofSeconds(5));

    verify(map).addEntryListener(any(), eq("test-key"), anyBoolean());
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
