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
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.journal.JournalCursor;
import org.jwcarman.substrate.journal.JournalExpiredException;

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

  @Test
  void consumerEscapesAbandonedJournalViaExpiredException() {
    InMemoryJournalSpi spi = new InMemoryJournalSpi();
    InMemoryNotifier notifier = new InMemoryNotifier();
    String key = spi.journalKey("escape-test");

    spi.create(key, Duration.ofMillis(200));

    DefaultJournal<String> journal =
        new DefaultJournal<>(
            spi, key, STRING_CODEC, notifier, Duration.ofDays(7), Duration.ofDays(30));

    journal.append("one-entry", Duration.ofHours(1));

    AtomicBoolean consumerExited = new AtomicBoolean(false);
    AtomicReference<JournalExpiredException> caught = new AtomicReference<>();

    Thread consumer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (JournalCursor<String> cursor = journal.readAfter("0-0")) {
                    while (cursor.isOpen()) {
                      try {
                        cursor.poll(Duration.ofMillis(50));
                      } catch (JournalExpiredException e) {
                        caught.set(e);
                        break;
                      }
                    }
                  }
                  consumerExited.set(true);
                });

    await()
        .atMost(Duration.ofMillis(2000))
        .untilAsserted(
            () -> {
              assertThat(consumerExited.get()).isTrue();
              assertThat(caught.get()).isNotNull();
            });
  }
}
