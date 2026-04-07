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
import org.jwcarman.substrate.spi.JournalEntry;
import org.jwcarman.substrate.spi.JournalSpi;

public class DefaultJournal implements Journal {

  private final JournalSpi journalSpi;
  private final String key;

  public DefaultJournal(JournalSpi journalSpi, String key) {
    this.journalSpi = journalSpi;
    this.key = key;
  }

  @Override
  public String append(String data) {
    return journalSpi.append(key, data);
  }

  @Override
  public String append(String data, Duration ttl) {
    return journalSpi.append(key, data, ttl);
  }

  @Override
  public Stream<JournalEntry> readAfter(String afterId) {
    return journalSpi.readAfter(key, afterId);
  }

  @Override
  public Stream<JournalEntry> readLast(int count) {
    return journalSpi.readLast(key, count);
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
}
