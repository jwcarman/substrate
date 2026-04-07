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
package org.jwcarman.substrate.core;

import java.time.Duration;
import java.util.stream.Stream;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.spi.JournalSpi;

public class DefaultJournal<T> implements Journal<T> {

  private final JournalSpi journalSpi;
  private final String key;
  private final Codec<T> codec;

  public DefaultJournal(JournalSpi journalSpi, String key, Codec<T> codec) {
    this.journalSpi = journalSpi;
    this.key = key;
    this.codec = codec;
  }

  @Override
  public String append(T data) {
    return journalSpi.append(key, codec.encode(data));
  }

  @Override
  public String append(T data, Duration ttl) {
    return journalSpi.append(key, codec.encode(data), ttl);
  }

  @Override
  public Stream<TypedJournalEntry<T>> readAfter(String afterId) {
    return journalSpi.readAfter(key, afterId).map(this::toTyped);
  }

  @Override
  public Stream<TypedJournalEntry<T>> readLast(int count) {
    return journalSpi.readLast(key, count).map(this::toTyped);
  }

  @Override
  public void complete() {
    journalSpi.complete(key);
  }

  @Override
  public void delete() {
    journalSpi.delete(key);
  }

  @Override
  public String key() {
    return key;
  }

  private TypedJournalEntry<T> toTyped(org.jwcarman.substrate.spi.JournalEntry entry) {
    return new TypedJournalEntry<>(
        entry.id(), entry.key(), codec.decode(entry.data()), entry.timestamp());
  }
}
