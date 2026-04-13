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
package org.jwcarman.substrate.core.journal;

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
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.jwcarman.substrate.journal.Journal;
import org.jwcarman.substrate.journal.JournalEntry;
import org.jwcarman.substrate.journal.JournalFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JournalFactoryTest {

  @Mock private CodecFactory codecFactory;
  @Mock private Codec<String> stringCodec;
  @Mock private Codec<List<String>> listCodec;

  private final ShutdownCoordinator coordinator = new ShutdownCoordinator();

  @Test
  void createReturnsBoundJournalWithPrefixedKey() {
    InMemoryJournalSpi spi = new InMemoryJournalSpi();
    when(codecFactory.create(String.class)).thenReturn(stringCodec);
    JournalFactory factory =
        new DefaultJournalFactory(
            spi,
            codecFactory,
            PayloadTransformer.IDENTITY,
            newNotifier(),
            JournalLimits.defaults(),
            coordinator);

    Journal<String> journal = factory.create("my-stream", String.class, Duration.ofHours(1));

    assertEquals("substrate:journal:my-stream", journal.key());
  }

  @Test
  void createdJournalDelegatesToSpi() {
    InMemoryJournalSpi spi = new InMemoryJournalSpi();
    when(codecFactory.create(String.class)).thenReturn(stringCodec);
    when(stringCodec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));
    when(stringCodec.decode(any(byte[].class)))
        .thenAnswer(inv -> new String((byte[]) inv.getArgument(0), UTF_8));
    JournalFactory factory =
        new DefaultJournalFactory(
            spi,
            codecFactory,
            PayloadTransformer.IDENTITY,
            newNotifier(),
            JournalLimits.defaults(),
            coordinator);

    Journal<String> journal = factory.create("test", String.class, Duration.ofHours(1));
    String id = journal.append("hello", Duration.ofHours(1));

    assertNotNull(id);
    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeAfter("0-0");
    try {
      NextResult<JournalEntry<String>> result = sub.next(Duration.ofSeconds(1));
      assertInstanceOf(NextResult.Value.class, result);
    } finally {
      sub.cancel();
    }
  }

  @Test
  void createWithTypeRefReturnsBoundJournal() {
    InMemoryJournalSpi spi = new InMemoryJournalSpi();
    TypeRef<List<String>> typeRef = new TypeRef<>() {};
    when(codecFactory.create(typeRef)).thenReturn(listCodec);
    lenient()
        .when(listCodec.encode(any()))
        .thenAnswer(inv -> inv.getArgument(0).toString().getBytes(UTF_8));
    JournalFactory factory =
        new DefaultJournalFactory(
            spi,
            codecFactory,
            PayloadTransformer.IDENTITY,
            newNotifier(),
            JournalLimits.defaults(),
            coordinator);

    Journal<List<String>> journal = factory.create("typed-stream", typeRef, Duration.ofHours(1));

    assertEquals("substrate:journal:typed-stream", journal.key());
    String id = journal.append(List.of("a", "b"), Duration.ofHours(1));
    assertNotNull(id);
  }

  private static Notifier newNotifier() {
    return new DefaultNotifier(
        new InMemoryNotifier(),
        new org.jwcarman.codec.jackson.JacksonCodecFactory(
            tools.jackson.databind.json.JsonMapper.builder().build()));
  }
}
