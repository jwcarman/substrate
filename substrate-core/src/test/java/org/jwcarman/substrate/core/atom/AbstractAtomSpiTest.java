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
package org.jwcarman.substrate.core.atom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AbstractAtomSpiTest {

  @Test
  void atomKeyPrependsPrefix() {
    StubAtomSpi spi = new StubAtomSpi("substrate:atom:");
    assertThat(spi.atomKey("session-1")).isEqualTo("substrate:atom:session-1");
  }

  @Test
  void atomKeyWithEmptyPrefix() {
    StubAtomSpi spi = new StubAtomSpi("");
    assertThat(spi.atomKey("session-1")).isEqualTo("session-1");
  }

  @Test
  void prefixReturnsConstructorValue() {
    StubAtomSpi spi = new StubAtomSpi("test:");
    assertThat(spi.exposedPrefix()).isEqualTo("test:");
  }

  @Test
  void sweepReturnsZero() {
    StubAtomSpi spi = new StubAtomSpi("prefix:");
    assertThat(spi.sweep(100)).isZero();
  }

  private static class StubAtomSpi extends AbstractAtomSpi {

    StubAtomSpi(String prefix) {
      super(prefix);
    }

    String exposedPrefix() {
      return prefix();
    }

    @Override
    public void create(String key, byte[] value, String token, Duration ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<RawAtom> read(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean set(String key, byte[] value, String token, Duration ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean touch(String key, Duration ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String key) {
      throw new UnsupportedOperationException();
    }
  }
}
