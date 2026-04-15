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
package org.jwcarman.substrate.core.atom;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomNotFoundException;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end strict-connect verification for atoms: wires a real {@link DefaultAtomFactory} around
 * an {@link InMemoryAtomSpi} and confirms that connecting to a non-existent name surfaces {@link
 * AtomNotFoundException} from the first real operation.
 */
class AtomStrictConnectEndToEndTest {

  private static final Codec<String> STRING_CODEC =
      new Codec<>() {
        @Override
        public byte[] encode(String value) {
          return value.getBytes(UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
          return new String(bytes, UTF_8);
        }
      };

  private static final CodecFactory CODEC_FACTORY =
      new CodecFactory() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Codec<T> create(Class<T> type) {
          return (Codec<T>) STRING_CODEC;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Codec<T> create(TypeRef<T> typeRef) {
          return (Codec<T>) STRING_CODEC;
        }
      };

  private final ShutdownCoordinator coordinator = new ShutdownCoordinator();
  private DefaultAtomFactory factory;

  @BeforeEach
  void setUp() {
    InMemoryAtomSpi spi = new InMemoryAtomSpi();
    Notifier notifier =
        new DefaultNotifier(
            new InMemoryNotifier(),
            new org.jwcarman.codec.jackson.JacksonCodecFactory(JsonMapper.builder().build()));
    factory =
        new DefaultAtomFactory(
            spi,
            CODEC_FACTORY,
            PayloadTransformer.IDENTITY,
            notifier,
            Duration.ofHours(24),
            coordinator);
  }

  @Test
  void connect_to_nonexistent_resource_throws_NotFoundException_on_first_operation() {
    Atom<String> atom = factory.connect("never-created", String.class);

    assertThatThrownBy(atom::get)
        .isInstanceOf(AtomNotFoundException.class)
        .hasMessageContaining("never-created");
  }

  @Test
  void connect_after_create_does_not_throw_on_first_operation() {
    factory.create("existing", String.class, "initial", Duration.ofMinutes(5));

    Atom<String> connected = factory.connect("existing", String.class);

    assertThatCode(() -> assertThat(connected.get().value()).isEqualTo("initial"))
        .doesNotThrowAnyException();
  }

  @Test
  void connect_sourced_delete_on_nonexistent_does_not_throw() {
    Atom<String> atom = factory.connect("never-created", String.class);

    assertThatCode(atom::delete).doesNotThrowAnyException();
  }
}
