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
package org.jwcarman.substrate.mailbox.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.spi.Notifier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NatsMailboxTest {

  @Mock private Connection connection;
  @Mock private Notifier notifier;
  @Mock private KeyValueManagement kvm;

  @Test
  void mailboxKeyUsesConfiguredPrefix() throws Exception {
    wireConnectionForConstruction();

    NatsMailbox mailbox = createMailbox();
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }

  @Test
  void deliverPutsValueAndNotifies() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);

    NatsMailbox mailbox = createMailbox();
    mailbox.deliver("substrate:mailbox:test", "hello");

    verify(kv).put(anyString(), any(byte[].class));
    verify(notifier).notify("substrate:mailbox:test", "hello");
  }

  @Test
  void awaitReturnsExistingValue() throws Exception {
    wireConnectionForConstruction();
    var kv = mock(io.nats.client.KeyValue.class);
    when(connection.keyValue("substrate-mailbox")).thenReturn(kv);

    KeyValueEntry entry = mock(KeyValueEntry.class);
    when(entry.getValue()).thenReturn("existing-value".getBytes(StandardCharsets.UTF_8));
    when(kv.get(anyString())).thenReturn(entry);

    NatsMailbox mailbox = createMailbox();
    var future = mailbox.await("substrate:mailbox:test", Duration.ofSeconds(5));

    assertThat(future).isCompletedWithValue("existing-value");
  }

  private void wireConnectionForConstruction() throws Exception {
    when(connection.keyValueManagement()).thenReturn(kvm);
    when(kvm.getStatus("substrate-mailbox")).thenReturn(mock(KeyValueStatus.class));
  }

  private NatsMailbox createMailbox() {
    return new NatsMailbox(
        connection, notifier, "substrate:mailbox:", "substrate-mailbox", Duration.ofMinutes(5));
  }
}
