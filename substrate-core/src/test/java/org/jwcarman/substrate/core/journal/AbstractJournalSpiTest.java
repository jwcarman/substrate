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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbstractJournalSpiTest {

  @Test
  void journalKeyPrependsPrefix() {
    StubJournalSpi spi = new StubJournalSpi("myprefix:");
    assertThat(spi.journalKey("myjournal")).isEqualTo("myprefix:myjournal");
  }

  @Test
  void journalKeyWithEmptyPrefix() {
    StubJournalSpi spi = new StubJournalSpi("");
    assertThat(spi.journalKey("myjournal")).isEqualTo("myjournal");
  }

  @Test
  void prefixReturnsConstructorValue() {
    StubJournalSpi spi = new StubJournalSpi("test:");
    assertThat(spi.exposedPrefix()).isEqualTo("test:");
  }

  @Test
  void generateEntryIdReturnsNonNullUuid() {
    StubJournalSpi spi = new StubJournalSpi("prefix:");
    String id = spi.exposedGenerateEntryId();
    assertThat(id).isNotNull().isNotEmpty();
  }

  @Test
  void generateEntryIdReturnsUniqueValues() {
    StubJournalSpi spi = new StubJournalSpi("prefix:");
    String id1 = spi.exposedGenerateEntryId();
    String id2 = spi.exposedGenerateEntryId();
    assertThat(id1).isNotEqualTo(id2);
  }

  /** Minimal concrete subclass that exposes protected methods for testing. */
  private static class StubJournalSpi extends AbstractJournalSpi {

    StubJournalSpi(String prefix) {
      super(prefix);
    }

    String exposedPrefix() {
      return prefix();
    }

    String exposedGenerateEntryId() {
      return generateEntryId();
    }

    @Override
    public String append(String key, byte[] data, Duration ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<RawJournalEntry> readAfter(String key, String afterId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<RawJournalEntry> readLast(String key, int count) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void complete(String key, Duration retentionTtl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isComplete(String key) {
      throw new UnsupportedOperationException();
    }
  }
}
