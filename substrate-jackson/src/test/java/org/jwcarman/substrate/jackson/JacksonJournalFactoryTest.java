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
package org.jwcarman.substrate.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class JacksonJournalFactoryTest {

  private JacksonJournalFactory factory;
  private InMemoryJournalSpi journal;

  @BeforeEach
  void setUp() {
    journal = new InMemoryJournalSpi();
    factory = new JacksonJournalFactory(journal, JsonMapper.builder().build());
  }

  @Test
  void createsJournalForClassType() {
    JacksonJournal<TestEvent> typed = factory.create(TestEvent.class);
    String key = typed.journalKey("class-type");
    typed.append(key, new TestEvent("hello", 42));

    try (Stream<JacksonJournalEntry<TestEvent>> stream = typed.readLast(key, 1)) {
      assertThat(stream.toList().getFirst().data()).isEqualTo(new TestEvent("hello", 42));
    }
  }

  @Test
  void createsJournalForTypeReference() {
    JacksonJournal<List<TestEvent>> typed = factory.create(new TypeReference<List<TestEvent>>() {});
    String key = typed.journalKey("type-ref");
    List<TestEvent> original = List.of(new TestEvent("a", 1), new TestEvent("b", 2));
    typed.append(key, original);

    try (Stream<JacksonJournalEntry<List<TestEvent>>> stream = typed.readLast(key, 1)) {
      assertThat(stream.toList().getFirst().data()).isEqualTo(original);
    }
  }

  record TestEvent(String name, int count) {}
}
