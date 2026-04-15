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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.jwcarman.substrate.mailbox.Mailbox;
import org.jwcarman.substrate.mailbox.MailboxNotFoundException;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end strict-connect verification for mailboxes: wires a real {@link DefaultMailboxFactory}
 * around an {@link InMemoryMailboxSpi} and confirms that connecting to a non-existent name surfaces
 * {@link MailboxNotFoundException} from the first real operation.
 */
class MailboxStrictConnectEndToEndTest {

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
  private DefaultMailboxFactory factory;

  @BeforeEach
  void setUp() {
    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    Notifier notifier =
        new DefaultNotifier(
            new InMemoryNotifier(),
            new org.jwcarman.codec.jackson.JacksonCodecFactory(JsonMapper.builder().build()));
    factory =
        new DefaultMailboxFactory(
            spi,
            CODEC_FACTORY,
            PayloadTransformer.IDENTITY,
            notifier,
            Duration.ofMinutes(30),
            coordinator);
  }

  @Test
  void connect_to_nonexistent_resource_throws_NotFoundException_on_first_operation() {
    Mailbox<String> mailbox = factory.connect("never-created", String.class);

    assertThatThrownBy(mailbox::subscribe)
        .isInstanceOf(MailboxNotFoundException.class)
        .hasMessageContaining("never-created");
  }

  @Test
  void connect_after_create_does_not_throw_on_first_operation() {
    factory.create("existing", String.class, Duration.ofMinutes(5));

    Mailbox<String> connected = factory.connect("existing", String.class);

    assertThatCode(
            () -> {
              try (BlockingSubscription<?> sub = connected.subscribe()) {
                assertThat(sub).isNotNull();
              }
            })
        .doesNotThrowAnyException();
  }

  @Test
  void connect_sourced_delete_on_nonexistent_does_not_throw() {
    Mailbox<String> mailbox = factory.connect("never-created", String.class);

    assertThatCode(mailbox::delete).doesNotThrowAnyException();
  }
}
