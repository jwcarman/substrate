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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;

class DefaultAtomFactoryTest {

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

  private static final CodecFactory CODEC_FACTORY =
      new CodecFactory() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Codec<T> create(Class<T> type) {
          return (Codec<T>) STRING_CODEC;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Codec<T> create(TypeRef<T> typeRef) {
          return (Codec<T>) STRING_CODEC;
        }
      };

  private final ShutdownCoordinator coordinator = new ShutdownCoordinator();
  private InMemoryAtomSpi spi;
  private InMemoryNotifier notifier;
  private DefaultAtomFactory factory;

  @BeforeEach
  void setUp() {
    spi = new InMemoryAtomSpi();
    notifier = new InMemoryNotifier();
    factory =
        new DefaultAtomFactory(spi, CODEC_FACTORY, notifier, Duration.ofHours(24), coordinator);
  }

  @Test
  void createWritesInitialValueAndReturnsAtom() {
    Atom<String> atom = factory.create("test", String.class, "hello", Duration.ofSeconds(10));

    assertThat(atom).isNotNull();
    assertThat(atom.get().value()).isEqualTo("hello");
  }

  @Test
  void createWithTypeRefWritesInitialValueAndReturnsAtom() {
    Atom<String> atom =
        factory.create("test", new TypeRef<String>() {}, "hello", Duration.ofSeconds(10));

    assertThat(atom).isNotNull();
    assertThat(atom.get().value()).isEqualTo("hello");
  }

  @Test
  void createThrowsOnNameCollision() {
    factory.create("test", String.class, "first", Duration.ofSeconds(10));

    Duration tenSeconds = Duration.ofSeconds(10);
    assertThatThrownBy(() -> factory.create("test", String.class, "second", tenSeconds))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void concurrentCreateExactlyOneSucceeds() throws InterruptedException {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      executor.submit(
          () -> {
            try {
              latch.await();
              factory.create("contested", String.class, "value-" + index, Duration.ofSeconds(10));
              successes.incrementAndGet();
            } catch (AtomAlreadyExistsException _) {
              failures.incrementAndGet();
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
          });
    }

    latch.countDown();
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threadCount - 1);
  }

  @Test
  void connectPerformsNoBackendIo() {
    AtomSpi failOnAnySpiCall =
        new AtomSpi() {
          @Override
          public void create(String key, byte[] value, String token, Duration ttl) {
            throw new AssertionError("SPI should not be called during connect");
          }

          @Override
          public java.util.Optional<RawAtom> read(String key) {
            throw new AssertionError("SPI should not be called during connect");
          }

          @Override
          public boolean set(String key, byte[] value, String token, Duration ttl) {
            throw new AssertionError("SPI should not be called during connect");
          }

          @Override
          public boolean touch(String key, Duration ttl) {
            throw new AssertionError("SPI should not be called during connect");
          }

          @Override
          public void delete(String key) {
            throw new AssertionError("SPI should not be called during connect");
          }

          @Override
          public int sweep(int maxToSweep) {
            throw new AssertionError("SPI should not be called during connect");
          }

          @Override
          public String atomKey(String name) {
            return "substrate:atom:" + name;
          }
        };

    DefaultAtomFactory lazyFactory =
        new DefaultAtomFactory(
            failOnAnySpiCall, CODEC_FACTORY, notifier, Duration.ofHours(24), coordinator);

    Atom<String> atom = lazyFactory.connect("test", String.class);
    assertThat(atom).isNotNull();
    assertThat(atom.key()).isEqualTo("substrate:atom:test");
  }

  @Test
  void connectWithTypeRefPerformsNoBackendIo() {
    Atom<String> atom = factory.connect("nonexistent", new TypeRef<String>() {});

    assertThat(atom).isNotNull();
  }

  @Test
  void connectedHandleThrowsOnGetWhenNoLiveAtom() {
    Atom<String> atom = factory.connect("nonexistent", String.class);

    assertThatThrownBy(atom::get).isInstanceOf(AtomExpiredException.class);
  }

  @Test
  void connectedHandleThrowsOnSetWhenNoLiveAtom() {
    Atom<String> atom = factory.connect("nonexistent", String.class);

    Duration tenSeconds = Duration.ofSeconds(10);
    assertThatThrownBy(() -> atom.set("value", tenSeconds))
        .isInstanceOf(AtomExpiredException.class);
  }

  @Test
  void connectedHandleTouchReturnsFalseWhenNoLiveAtom() {
    Atom<String> atom = factory.connect("nonexistent", String.class);

    assertThat(atom.touch(Duration.ofSeconds(10))).isFalse();
  }

  @Test
  void createThrowsWhenTtlExceedsMaxTtl() {
    Duration excessiveTtl = Duration.ofHours(25);
    assertThatThrownBy(() -> factory.create("excessive", String.class, "value", excessiveTtl))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds configured maximum");
  }
}
