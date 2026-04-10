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
package org.jwcarman.substrate.redis.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.jwcarman.substrate.redis.AbstractRedisIT;

class RedisAtomIT extends AbstractRedisIT {

  private RedisClient client;
  private RedisAtomSpi atom;

  @BeforeEach
  void setUp() {
    client =
        RedisClient.create(
            RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getFirstMappedPort())
                .build());
    RedisCommands<String, String> commands = client.connect(StringCodec.UTF8).sync();
    atom = new RedisAtomSpi(commands, "substrate:atom:");
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.shutdown();
    }
  }

  @Test
  void createAndReadRoundTrip() {
    String key = atom.atomKey("round-trip");
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
    String token = "tok-1";

    atom.create(key, value, token, Duration.ofMinutes(5));

    Optional<RawAtom> result = atom.read(key);
    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo(value);
    assertThat(result.get().token()).isEqualTo(token);
  }

  @Test
  void createThrowsWhenKeyAlreadyExists() {
    String key = atom.atomKey("duplicate");
    byte[] value = "first".getBytes(StandardCharsets.UTF_8);

    atom.create(key, value, "tok-1", Duration.ofMinutes(5));

    assertThatThrownBy(
            () ->
                atom.create(
                    key, "second".getBytes(StandardCharsets.UTF_8), "tok-2", Duration.ofMinutes(5)))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void readReturnsEmptyForAbsentKey() {
    String key = atom.atomKey("missing");

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void setUpdatesExistingKey() {
    String key = atom.atomKey("set-existing");
    byte[] original = "original".getBytes(StandardCharsets.UTF_8);
    byte[] updated = "updated".getBytes(StandardCharsets.UTF_8);

    atom.create(key, original, "tok-1", Duration.ofMinutes(5));

    boolean result = atom.set(key, updated, "tok-2", Duration.ofMinutes(5));
    assertThat(result).isTrue();

    Optional<RawAtom> read = atom.read(key);
    assertThat(read).isPresent();
    assertThat(read.get().value()).isEqualTo(updated);
    assertThat(read.get().token()).isEqualTo("tok-2");
  }

  @Test
  void setReturnsFalseForMissingKey() {
    String key = atom.atomKey("set-missing");

    boolean result =
        atom.set(key, "value".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));
    assertThat(result).isFalse();
  }

  @Test
  void touchExtendsTtl() {
    String key = atom.atomKey("touch-extend");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofSeconds(2));

    boolean result = atom.touch(key, Duration.ofMinutes(5));
    assertThat(result).isTrue();

    assertThat(atom.read(key)).isPresent();
  }

  @Test
  void touchReturnsFalseForMissingKey() {
    String key = atom.atomKey("touch-missing");

    assertThat(atom.touch(key, Duration.ofMinutes(5))).isFalse();
  }

  @Test
  void deleteRemovesKey() {
    String key = atom.atomKey("delete-me");
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofMinutes(5));

    atom.delete(key);

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void deleteIsIdempotent() {
    String key = atom.atomKey("delete-twice");

    atom.delete(key);
    atom.delete(key);
  }

  @Test
  void ttlExpiryMakesKeyDisappear() {
    String key = atom.atomKey("ttl-expiry");
    atom.create(key, "ephemeral".getBytes(StandardCharsets.UTF_8), "tok-1", Duration.ofSeconds(1));

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(atom.read(key)).isEmpty());
  }

  @Test
  void concurrentCreateExactlyOneSucceeds() {
    String key = atom.atomKey("concurrent-create");
    int threadCount = 8;
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    IntStream.range(0, threadCount)
        .mapToObj(
            i ->
                Thread.ofVirtual()
                    .start(
                        () -> {
                          try {
                            atom.create(
                                key,
                                ("payload-" + i).getBytes(StandardCharsets.UTF_8),
                                "tok-" + i,
                                Duration.ofMinutes(5));
                            successes.incrementAndGet();
                          } catch (AtomAlreadyExistsException _) {
                            failures.incrementAndGet();
                          }
                        }))
        .toList()
        .forEach(
            t -> {
              try {
                t.join();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threadCount - 1);
  }

  @Test
  void sweepReturnsZero() {
    assertThat(atom.sweep(100)).isZero();
  }

  @Test
  void atomKeyUsesPrefix() {
    assertThat(atom.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }
}
