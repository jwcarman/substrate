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
package org.jwcarman.substrate.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

  @Test
  void accessorsReturnConstructorArguments() {
    Instant ts = Instant.parse("2026-04-11T00:00:00Z");
    JournalEntry<String> entry = new JournalEntry<>("entry-id", "journal-key", "payload", ts);

    assertThat(entry.id()).isEqualTo("entry-id");
    assertThat(entry.key()).isEqualTo("journal-key");
    assertThat(entry.data()).isEqualTo("payload");
    assertThat(entry.timestamp()).isEqualTo(ts);
  }

  @Test
  void equalsAndHashCodeMatchForIdenticalContent() {
    Instant ts = Instant.parse("2026-04-11T00:00:00Z");
    JournalEntry<String> a = new JournalEntry<>("id", "key", "data", ts);
    JournalEntry<String> b = new JournalEntry<>("id", "key", "data", ts);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }
}
