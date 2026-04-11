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
package org.jwcarman.substrate.dynamodb.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.dynamodb.AbstractDynamoDbIT;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

class DynamoDbAtomIT extends AbstractDynamoDbIT {

  private DynamoDbAtomSpi atom;

  @BeforeEach
  void setUp() {
    DynamoDbClient client = createClient();

    try {
      client.deleteTable(DeleteTableRequest.builder().tableName("substrate_atoms").build());
    } catch (ResourceNotFoundException _) {
      // table doesn't exist yet
    }

    atom = new DynamoDbAtomSpi(client, "substrate:atom:", "substrate_atoms");
    atom.createTable();
  }

  @Test
  void createAndRead() {
    String key = atom.atomKey("test-" + System.nanoTime());
    atom.create(key, "hello".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    var result = atom.read(key);

    assertThat(result).isPresent();
    assertThat(new String(result.get().value(), StandardCharsets.UTF_8)).isEqualTo("hello");
    assertThat(result.get().token()).isEqualTo("tok1");
  }

  @Test
  void createThrowsOnDuplicate() {
    String key = atom.atomKey("dup-" + System.nanoTime());
    atom.create(key, "first".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    byte[] value = "second".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofMinutes(5);
    assertThatThrownBy(() -> atom.create(key, value, "tok2", ttl))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void concurrentCreateExactlyOneWins() throws InterruptedException {
    String key = atom.atomKey("race-" + System.nanoTime());
    int threads = 5;
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        int idx = i;
        executor.submit(
            () -> {
              try {
                latch.await();
                atom.create(
                    key,
                    ("val" + idx).getBytes(StandardCharsets.UTF_8),
                    "tok" + idx,
                    Duration.ofMinutes(5));
                successes.incrementAndGet();
              } catch (AtomAlreadyExistsException _) {
                failures.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }
      latch.countDown();
      executor.shutdown();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threads - 1);
  }

  @Test
  void readReturnsEmptyForAbsentKey() {
    assertThat(atom.read(atom.atomKey("nonexistent-" + System.nanoTime()))).isEmpty();
  }

  @Test
  void setUpdatesExistingAtom() {
    String key = atom.atomKey("set-" + System.nanoTime());
    atom.create(key, "original".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    boolean applied =
        atom.set(key, "updated".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5));

    assertThat(applied).isTrue();
    var result = atom.read(key);
    assertThat(result).isPresent();
    assertThat(new String(result.get().value(), StandardCharsets.UTF_8)).isEqualTo("updated");
    assertThat(result.get().token()).isEqualTo("tok2");
  }

  @Test
  void setReturnsFalseForAbsentKey() {
    boolean applied =
        atom.set(
            atom.atomKey("missing-" + System.nanoTime()),
            "val".getBytes(StandardCharsets.UTF_8),
            "tok",
            Duration.ofMinutes(5));

    assertThat(applied).isFalse();
  }

  @Test
  void touchExtendsExistingAtom() {
    String key = atom.atomKey("touch-" + System.nanoTime());
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    boolean applied = atom.touch(key, Duration.ofMinutes(10));

    assertThat(applied).isTrue();
    assertThat(atom.read(key)).isPresent();
  }

  @Test
  void touchReturnsFalseForAbsentKey() {
    assertThat(atom.touch(atom.atomKey("missing-" + System.nanoTime()), Duration.ofMinutes(5)))
        .isFalse();
  }

  @Test
  void deleteRemovesAtom() {
    String key = atom.atomKey("del-" + System.nanoTime());
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    atom.delete(key);

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void deleteIsIdempotent() {
    String key = atom.atomKey("del2-" + System.nanoTime());
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofMinutes(5));

    atom.delete(key);
    atom.delete(key);

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void readReturnEmptyForExpiredItem() {
    String key = atom.atomKey("ttl-" + System.nanoTime());
    atom.create(key, "ephemeral".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofSeconds(1));

    // Wait for the TTL to pass — the read-side check filters expired items
    // even before DynamoDB's eventual sweep removes them physically.
    try {
      Thread.sleep(1500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(atom.read(key)).isEmpty();
  }

  @Test
  void setReturnsFalseAfterTtlExpiry() {
    String key = atom.atomKey("ttlset-" + System.nanoTime());
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofSeconds(1));

    try {
      Thread.sleep(1500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(atom.set(key, "new".getBytes(StandardCharsets.UTF_8), "tok2", Duration.ofMinutes(5)))
        .isFalse();
  }

  @Test
  void touchReturnsFalseAfterTtlExpiry() {
    String key = atom.atomKey("ttltouch-" + System.nanoTime());
    atom.create(key, "data".getBytes(StandardCharsets.UTF_8), "tok1", Duration.ofSeconds(1));

    try {
      Thread.sleep(1500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(atom.touch(key, Duration.ofMinutes(5))).isFalse();
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(atom.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void sweepReturnsZero() {
    assertThat(atom.sweep(100)).isZero();
  }

  @Test
  void createTableIsIdempotent() {
    assertThatNoException()
        .isThrownBy(
            () -> {
              atom.createTable();
              atom.createTable();
            });
  }
}
