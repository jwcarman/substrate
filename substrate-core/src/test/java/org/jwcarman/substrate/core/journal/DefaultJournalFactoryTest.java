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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.journal.Journal;
import org.jwcarman.substrate.journal.JournalAlreadyExistsException;
import org.jwcarman.substrate.journal.JournalExpiredException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultJournalFactoryTest {

  @Mock private CodecFactory codecFactory;
  @Mock private Codec<String> stringCodec;

  private final ShutdownCoordinator coordinator = new ShutdownCoordinator();
  private InMemoryJournalSpi spi;
  private DefaultJournalFactory factory;

  @BeforeEach
  void setUp() {
    spi = new InMemoryJournalSpi();
    lenient().when(codecFactory.create(String.class)).thenReturn(stringCodec);
    lenient()
        .when(stringCodec.encode(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(UTF_8));
    lenient()
        .when(stringCodec.decode(any(byte[].class)))
        .thenAnswer(inv -> new String((byte[]) inv.getArgument(0), UTF_8));
    Notifier notifier =
        new DefaultNotifier(
            new InMemoryNotifier(),
            new org.jwcarman.codec.jackson.JacksonCodecFactory(
                tools.jackson.databind.json.JsonMapper.builder().build()));
    factory =
        new DefaultJournalFactory(
            spi, codecFactory, notifier, JournalLimits.defaults(), coordinator);
  }

  @Test
  void createReturnsJournalWithPrefixedKey() {
    Journal<String> journal = factory.create("my-stream", String.class, Duration.ofHours(1));
    assertThat(journal.key()).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void createThrowsWhenInactivityTtlExceedsMax() {
    Duration excessiveInactivityTtl = Duration.ofHours(48);
    assertThatThrownBy(() -> factory.create("test", String.class, excessiveInactivityTtl))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createThrowsOnDuplicateJournal() {
    factory.create("test", String.class, Duration.ofHours(1));

    Duration oneHour = Duration.ofHours(1);
    assertThatThrownBy(() -> factory.create("test", String.class, oneHour))
        .isInstanceOf(JournalAlreadyExistsException.class);
  }

  @Test
  void connectReturnsLazyHandle() {
    Journal<String> journal = factory.connect("lazy-test", String.class);
    assertThat(journal.key()).isEqualTo("substrate:journal:lazy-test");
  }

  @Test
  void connectedHandleThrowsOnFirstOperationIfNoLiveJournal() {
    Journal<String> journal = factory.connect("nonexistent", String.class);

    Duration oneHour = Duration.ofHours(1);
    assertThatThrownBy(() -> journal.append("data", oneHour))
        .isInstanceOf(JournalExpiredException.class);
  }

  @Test
  void createWithTypeRefReturnsJournalWithPrefixedKey() {
    TypeRef<String> typeRef = new TypeRef<>() {};
    lenient().when(codecFactory.create(typeRef)).thenReturn(stringCodec);

    Journal<String> journal = factory.create("my-stream", typeRef, Duration.ofHours(1));
    assertThat(journal.key()).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void createWithTypeRefThrowsWhenInactivityTtlExceedsMax() {
    TypeRef<String> typeRef = new TypeRef<>() {};
    Duration excessiveInactivityTtl = Duration.ofHours(48);
    assertThatThrownBy(() -> factory.create("test", typeRef, excessiveInactivityTtl))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void connectWithTypeRefReturnsLazyHandle() {
    TypeRef<String> typeRef = new TypeRef<>() {};
    lenient().when(codecFactory.create(typeRef)).thenReturn(stringCodec);

    Journal<String> journal = factory.connect("lazy-test", typeRef);
    assertThat(journal.key()).isEqualTo("substrate:journal:lazy-test");
  }

  @Test
  void connectedHandleWorksIfJournalWasCreated() {
    factory.create("existing", String.class, Duration.ofHours(1));

    Journal<String> connected = factory.connect("existing", String.class);
    String id = connected.append("data", Duration.ofHours(1));

    assertThat(id).isNotNull();
  }
}
