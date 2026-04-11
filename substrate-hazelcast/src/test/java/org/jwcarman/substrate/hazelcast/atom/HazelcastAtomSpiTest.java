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
package org.jwcarman.substrate.hazelcast.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HazelcastAtomSpiTest {

  @Mock private HazelcastInstance hazelcastInstance;
  @Mock private IMap<String, AtomEntry> map;

  private HazelcastAtomSpi spi;

  @BeforeEach
  void setUp() {
    when(hazelcastInstance.<String, AtomEntry>getMap("substrate-atoms")).thenReturn(map);
    spi = new HazelcastAtomSpi(hazelcastInstance, "substrate:atom:", "substrate-atoms");
  }

  @Test
  void createSucceedsWhenKeyAbsent() {
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
    when(map.putIfAbsent(anyString(), any(AtomEntry.class), anyLong(), any(TimeUnit.class)))
        .thenReturn(null);

    spi.create("test-key", value, "token-1", Duration.ofMinutes(5));

    verify(map)
        .putIfAbsent(eq("test-key"), any(AtomEntry.class), eq(300000L), eq(TimeUnit.MILLISECONDS));
  }

  @Test
  void createThrowsWhenKeyExists() {
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
    when(map.putIfAbsent(anyString(), any(AtomEntry.class), anyLong(), any(TimeUnit.class)))
        .thenReturn(new AtomEntry("old".getBytes(StandardCharsets.UTF_8), "old-token"));

    Duration ttl = Duration.ofMinutes(5);
    assertThatThrownBy(() -> spi.create("test-key", value, "token-1", ttl))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void readReturnsValueWhenPresent() {
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
    when(map.get("test-key")).thenReturn(new AtomEntry(value, "token-1"));

    Optional<RawAtom> result = spi.read("test-key");

    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo(value);
    assertThat(result.get().token()).isEqualTo("token-1");
  }

  @Test
  void readReturnsEmptyWhenAbsent() {
    when(map.get("test-key")).thenReturn(null);

    Optional<RawAtom> result = spi.read("test-key");

    assertThat(result).isEmpty();
  }

  @Test
  void setReturnsTrueWhenKeyExists() {
    byte[] value = "updated".getBytes(StandardCharsets.UTF_8);
    when(map.replace(anyString(), any(AtomEntry.class)))
        .thenReturn(new AtomEntry("old".getBytes(StandardCharsets.UTF_8), "old-token"));
    when(map.setTtl(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

    boolean result = spi.set("test-key", value, "token-2", Duration.ofSeconds(60));

    assertThat(result).isTrue();
    verify(map).replace(eq("test-key"), any(AtomEntry.class));
    verify(map).setTtl("test-key", 60000L, TimeUnit.MILLISECONDS);
  }

  @Test
  void setReturnsFalseWhenKeyAbsent() {
    byte[] value = "updated".getBytes(StandardCharsets.UTF_8);
    when(map.replace(anyString(), any(AtomEntry.class))).thenReturn(null);

    boolean result = spi.set("test-key", value, "token-2", Duration.ofSeconds(60));

    assertThat(result).isFalse();
  }

  @Test
  void touchReturnsTrueWhenKeyExists() {
    when(map.setTtl("test-key", 60000L, TimeUnit.MILLISECONDS)).thenReturn(true);

    boolean result = spi.touch("test-key", Duration.ofSeconds(60));

    assertThat(result).isTrue();
  }

  @Test
  void touchReturnsFalseWhenKeyAbsent() {
    when(map.setTtl("test-key", 60000L, TimeUnit.MILLISECONDS)).thenReturn(false);

    boolean result = spi.touch("test-key", Duration.ofSeconds(60));

    assertThat(result).isFalse();
  }

  @Test
  void deleteRemovesKeyFromMap() {
    spi.delete("test-key");

    verify(map).delete("test-key");
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(spi.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void sweepReturnsZero() {
    assertThat(spi.sweep(100)).isZero();
  }
}
