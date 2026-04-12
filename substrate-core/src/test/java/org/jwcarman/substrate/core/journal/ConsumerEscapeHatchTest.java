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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.NextResult;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.DefaultNotifier;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.journal.JournalEntry;
import tools.jackson.databind.json.JsonMapper;

class ConsumerEscapeHatchTest {

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

  @Test
  void consumerEscapesAbandonedJournalViaExpired() {
    InMemoryJournalSpi spi = new InMemoryJournalSpi();
    Notifier notifier = new DefaultNotifier(new InMemoryNotifier(), CODEC_FACTORY);
    String key = spi.journalKey("escape-test");

    spi.create(key, Duration.ofMillis(200));

    DefaultJournal<String> journal =
        new DefaultJournal<>(
            spi, key, STRING_CODEC, notifier, JournalLimits.defaults(), new ShutdownCoordinator());

    journal.append("one-entry", Duration.ofHours(1));

    AtomicBoolean consumerExited = new AtomicBoolean(false);
    AtomicReference<NextResult<JournalEntry<String>>> expiredResult = new AtomicReference<>();

    Thread.ofVirtual()
        .start(
            () -> {
              BlockingSubscription<JournalEntry<String>> sub = journal.subscribeAfter("0-0");
              try {
                loop:
                while (sub.isActive()) {
                  NextResult<JournalEntry<String>> result = sub.next(Duration.ofMillis(50));
                  switch (result) {
                    case NextResult.Expired<JournalEntry<String>> e -> {
                      expiredResult.set(e);
                      break loop;
                    }
                    default -> {
                      /* not relevant to this test */
                    }
                  }
                }
              } finally {
                sub.cancel();
              }
              consumerExited.set(true);
            });

    await()
        .atMost(Duration.ofMillis(2000))
        .untilAsserted(
            () -> {
              assertThat(consumerExited.get()).isTrue();
              assertThat(expiredResult.get()).isNotNull();
              assertThat(expiredResult.get()).isInstanceOf(NextResult.Expired.class);
            });
  }
}
