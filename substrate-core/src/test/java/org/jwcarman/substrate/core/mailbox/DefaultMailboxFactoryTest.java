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
package org.jwcarman.substrate.core.mailbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.mailbox.Mailbox;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultMailboxFactoryTest {

  @Mock private CodecFactory codecFactory;
  @Mock private Codec<String> stringCodec;

  @Test
  void connectDoesNotCallSpiCreate() {
    MailboxSpi spi = mock(MailboxSpi.class);
    when(spi.mailboxKey("test")).thenReturn("substrate:mailbox:test");
    lenient().when(spi.get(anyString())).thenThrow(new MailboxExpiredException("test"));
    when(codecFactory.create(String.class)).thenReturn(stringCodec);

    DefaultMailboxFactory factory =
        new DefaultMailboxFactory(
            spi, codecFactory, new InMemoryNotifier(), Duration.ofMinutes(30));

    Mailbox<String> mailbox = factory.connect("test", String.class);

    assertThat(mailbox.key()).isEqualTo("substrate:mailbox:test");
    verify(spi, never()).create(anyString(), any(Duration.class));
  }

  @Test
  void connectedHandleThrowsOnFirstOperationIfNoLiveMailbox() {
    when(codecFactory.create(String.class)).thenReturn(stringCodec);
    when(stringCodec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));

    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    DefaultMailboxFactory factory =
        new DefaultMailboxFactory(
            spi, codecFactory, new InMemoryNotifier(), Duration.ofMinutes(30));

    Mailbox<String> mailbox = factory.connect("nonexistent", String.class);

    assertThatThrownBy(() -> mailbox.deliver("hello")).isInstanceOf(MailboxExpiredException.class);
  }

  @Test
  void connectedHandleWorksIfMailboxWasCreated() {
    when(codecFactory.create(String.class)).thenReturn(stringCodec);
    when(stringCodec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));
    when(stringCodec.decode(any(byte[].class)))
        .thenAnswer(inv -> new String((byte[]) inv.getArgument(0), UTF_8));

    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    InMemoryNotifier notifier = new InMemoryNotifier();
    DefaultMailboxFactory factory =
        new DefaultMailboxFactory(spi, codecFactory, notifier, Duration.ofMinutes(30));

    Mailbox<String> created = factory.create("existing", String.class, Duration.ofMinutes(5));
    Mailbox<String> connected = factory.connect("existing", String.class);

    connected.deliver("hello");

    BlockingSubscription<String> sub = created.subscribe();
    NextResult<String> result = sub.next(Duration.ofSeconds(5));
    assertThat(result).isInstanceOf(NextResult.Value.class);
    assertThat(((NextResult.Value<String>) result).value()).isEqualTo("hello");
  }
}
