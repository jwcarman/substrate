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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.memory.InMemoryMailboxSpi;
import org.jwcarman.substrate.memory.InMemoryNotifier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MailboxFactoryTest {

  @Mock private CodecFactory codecFactory;
  @Mock private Codec<String> stringCodec;
  @Mock private Codec<List<String>> listCodec;

  @Test
  void createReturnsBoundMailboxWithPrefixedKey() {
    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    InMemoryNotifier notifier = new InMemoryNotifier();
    when(codecFactory.create(String.class)).thenReturn(stringCodec);
    MailboxFactory factory = new MailboxFactory(spi, codecFactory, notifier);

    Mailbox<String> mailbox = factory.create("my-elicit", String.class);

    assertEquals("substrate:mailbox:my-elicit", mailbox.key());
  }

  @Test
  void createdMailboxDelegatesToSpi() {
    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    InMemoryNotifier notifier = new InMemoryNotifier();
    when(codecFactory.create(String.class)).thenReturn(stringCodec);
    when(stringCodec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));
    when(stringCodec.decode(any(byte[].class)))
        .thenAnswer(inv -> new String((byte[]) inv.getArgument(0), UTF_8));
    MailboxFactory factory = new MailboxFactory(spi, codecFactory, notifier);

    Mailbox<String> mailbox = factory.create("test", String.class);
    mailbox.deliver("hello");

    assertEquals("hello", mailbox.poll(Duration.ofSeconds(1)).orElseThrow());
  }

  @Test
  void createWithTypeRefReturnsBoundMailbox() {
    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    InMemoryNotifier notifier = new InMemoryNotifier();
    TypeRef<List<String>> typeRef = new TypeRef<>() {};
    when(codecFactory.create(typeRef)).thenReturn(listCodec);
    lenient()
        .when(listCodec.encode(any()))
        .thenAnswer(inv -> inv.getArgument(0).toString().getBytes(UTF_8));
    lenient().when(listCodec.decode(any(byte[].class))).thenAnswer(inv -> List.of("decoded"));
    MailboxFactory factory = new MailboxFactory(spi, codecFactory, notifier);

    Mailbox<List<String>> mailbox = factory.create("typed-elicit", typeRef);

    assertEquals("substrate:mailbox:typed-elicit", mailbox.key());
  }
}
