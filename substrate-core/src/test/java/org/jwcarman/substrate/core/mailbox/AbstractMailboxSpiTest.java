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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AbstractMailboxSpiTest {

  @Test
  void mailboxKeyPrependsPrefix() {
    StubMailboxSpi spi = new StubMailboxSpi("myprefix:");
    assertThat(spi.mailboxKey("inbox")).isEqualTo("myprefix:inbox");
  }

  @Test
  void mailboxKeyWithEmptyPrefix() {
    StubMailboxSpi spi = new StubMailboxSpi("");
    assertThat(spi.mailboxKey("inbox")).isEqualTo("inbox");
  }

  @Test
  void prefixReturnsConstructorValue() {
    StubMailboxSpi spi = new StubMailboxSpi("test:");
    assertThat(spi.exposedPrefix()).isEqualTo("test:");
  }

  /** Minimal concrete subclass that exposes protected methods for testing. */
  private static class StubMailboxSpi extends AbstractMailboxSpi {

    StubMailboxSpi(String prefix) {
      super(prefix);
    }

    String exposedPrefix() {
      return prefix();
    }

    @Override
    public void create(String key, Duration ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deliver(String key, byte[] value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<byte[]> get(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String key) {
      throw new UnsupportedOperationException();
    }
  }
}
