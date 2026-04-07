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

import java.time.Duration;
import java.util.stream.Stream;
import org.jwcarman.substrate.spi.Journal;
import org.jwcarman.substrate.spi.JournalEntry;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

public class JacksonJournal<T> {

  private final Journal journal;
  private final ObjectMapper objectMapper;
  private final JavaType javaType;

  public JacksonJournal(Journal journal, ObjectMapper objectMapper, JavaType javaType) {
    this.journal = journal;
    this.objectMapper = objectMapper;
    this.javaType = javaType;
  }

  public JacksonJournal(Journal journal, ObjectMapper objectMapper, Class<T> type) {
    this(journal, objectMapper, objectMapper.constructType(type));
  }

  public String append(String key, T data) {
    return journal.append(key, objectMapper.writeValueAsString(data));
  }

  public String append(String key, T data, Duration ttl) {
    return journal.append(key, objectMapper.writeValueAsString(data), ttl);
  }

  public Stream<JacksonJournalEntry<T>> readAfter(String key, String afterId) {
    return journal.readAfter(key, afterId).map(this::toTypedEntry);
  }

  public Stream<JacksonJournalEntry<T>> readLast(String key, int count) {
    return journal.readLast(key, count).map(this::toTypedEntry);
  }

  public void complete(String key) {
    journal.complete(key);
  }

  public void delete(String key) {
    journal.delete(key);
  }

  public String journalKey(String name) {
    return journal.journalKey(name);
  }

  private JacksonJournalEntry<T> toTypedEntry(JournalEntry entry) {
    return new JacksonJournalEntry<>(
        entry.id(), entry.key(), objectMapper.readValue(entry.data(), javaType), entry.timestamp());
  }
}
