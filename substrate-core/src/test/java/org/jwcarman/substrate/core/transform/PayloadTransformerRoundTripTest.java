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
package org.jwcarman.substrate.core.transform;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.core.atom.DefaultAtomFactory;
import org.jwcarman.substrate.core.journal.DefaultJournal;
import org.jwcarman.substrate.core.journal.JournalContext;
import org.jwcarman.substrate.core.journal.JournalLimits;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.mailbox.DefaultMailbox;
import org.jwcarman.substrate.core.mailbox.MailboxContext;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.journal.JournalEntry;
import tools.jackson.databind.json.JsonMapper;

class PayloadTransformerRoundTripTest {

  private static final PayloadTransformer XOR_TRANSFORMER =
      new PayloadTransformer() {
        @Override
        public byte[] encode(byte[] plaintext) {
          byte[] result = new byte[plaintext.length];
          for (int i = 0; i < plaintext.length; i++) {
            result[i] = (byte) (plaintext[i] ^ 0xFF);
          }
          return result;
        }

        @Override
        public byte[] decode(byte[] ciphertext) {
          return encode(ciphertext);
        }
      };

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
      new JacksonCodecFactory(JsonMapper.builder().build());

  private final ShutdownCoordinator coordinator = new ShutdownCoordinator();
  private Notifier notifier;

  @BeforeEach
  void setUp() {
    notifier = new DefaultNotifier(new InMemoryNotifier(), CODEC_FACTORY);
  }

  @Test
  void atomSetAndGetRoundTripsWithXorTransformer() {
    InMemoryAtomSpi spi = new InMemoryAtomSpi();

    DefaultAtomFactory factory =
        new DefaultAtomFactory(
            spi, CODEC_FACTORY, XOR_TRANSFORMER, notifier, Duration.ofHours(24), coordinator);

    Atom<String> atom = factory.create("xor-test", String.class, "hello", Duration.ofSeconds(10));

    assertThat(atom.get().value()).isEqualTo("hello");

    atom.set("updated", Duration.ofSeconds(10));

    assertThat(atom.get().value()).isEqualTo("updated");
  }

  @Test
  void atomTokensComputedFromPlaintextNotCiphertext() {
    InMemoryAtomSpi spi = new InMemoryAtomSpi();

    DefaultAtomFactory factory =
        new DefaultAtomFactory(
            spi, CODEC_FACTORY, XOR_TRANSFORMER, notifier, Duration.ofHours(24), coordinator);

    Atom<String> atom = factory.create("token-test", String.class, "value", Duration.ofSeconds(10));
    String token1 = atom.get().token();

    atom.set("value", Duration.ofSeconds(10));
    String token2 = atom.get().token();

    assertThat(token1).isEqualTo(token2);
  }

  @Test
  void journalAppendAndSubscribeRoundTripsWithXorTransformer() {
    InMemoryJournalSpi spi = new InMemoryJournalSpi();
    String key = spi.journalKey("xor-test");
    spi.create(key, Duration.ofHours(1));

    JournalContext journalContext =
        new JournalContext(spi, XOR_TRANSFORMER, notifier, JournalLimits.defaults(), coordinator);
    DefaultJournal<String> journal = new DefaultJournal<>(journalContext, key, STRING_CODEC, false);

    journal.append("event-1", Duration.ofMinutes(5));

    BlockingSubscription<JournalEntry<String>> sub = journal.subscribeLast(1);
    try {
      NextResult<JournalEntry<String>> result = sub.next(Duration.ofSeconds(5));

      assertThat(result).isInstanceOf(NextResult.Value.class);
      assertThat(((NextResult.Value<JournalEntry<String>>) result).value().data())
          .isEqualTo("event-1");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void mailboxDeliverAndSubscribeRoundTripsWithXorTransformer() {
    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    String key = spi.mailboxKey("xor-test");
    spi.create(key, Duration.ofMinutes(5));

    MailboxContext mailboxContext = new MailboxContext(spi, XOR_TRANSFORMER, notifier, coordinator);
    DefaultMailbox<String> mailbox = new DefaultMailbox<>(mailboxContext, key, STRING_CODEC, false);

    mailbox.deliver("payload");

    BlockingSubscription<String> sub = mailbox.subscribe();
    try {
      NextResult<String> result = sub.next(Duration.ofSeconds(5));

      assertThat(result).isInstanceOf(NextResult.Value.class);
      assertThat(((NextResult.Value<String>) result).value()).isEqualTo("payload");
    } finally {
      sub.cancel();
    }
  }

  @Test
  void xorTransformerActuallyTransformsBytes() {
    byte[] plaintext = "hello".getBytes(UTF_8);

    byte[] encoded = XOR_TRANSFORMER.encode(plaintext);

    assertThat(encoded).isNotEqualTo(plaintext);
    assertThat(XOR_TRANSFORMER.decode(encoded)).isEqualTo(plaintext);
  }
}
