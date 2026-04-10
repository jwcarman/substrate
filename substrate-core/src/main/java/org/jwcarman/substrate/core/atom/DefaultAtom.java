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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.atom.Snapshot;
import org.jwcarman.substrate.spi.Notifier;
import org.jwcarman.substrate.spi.NotifierSubscription;

public class DefaultAtom<T> implements Atom<T> {

  private final AtomSpi atomSpi;
  private final String key;
  private final Codec<T> codec;
  private final Notifier notifier;

  public DefaultAtom(AtomSpi atomSpi, String key, Codec<T> codec, Notifier notifier) {
    this.atomSpi = atomSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
  }

  @Override
  public void set(T data, Duration ttl) {
    byte[] bytes = codec.encode(data);
    String newToken = token(bytes);
    boolean alive = atomSpi.set(key, bytes, newToken, ttl);
    if (!alive) {
      throw new AtomExpiredException(key);
    }
    notifier.notify(key, newToken);
  }

  @Override
  public boolean touch(Duration ttl) {
    return atomSpi.touch(key, ttl);
  }

  @Override
  public Snapshot<T> get() {
    AtomRecord record = atomSpi.read(key).orElseThrow(() -> new AtomExpiredException(key));
    return new Snapshot<>(codec.decode(record.value()), record.token());
  }

  @Override
  public Optional<Snapshot<T>> watch(Snapshot<T> lastSeen, Duration timeout) {
    Semaphore semaphore = new Semaphore(0);
    NotifierSubscription subscription =
        notifier.subscribe(
            (notifiedKey, payload) -> {
              if (key.equals(notifiedKey)) {
                semaphore.release();
              }
            });
    try {
      Instant deadline = Instant.now().plus(timeout);

      while (true) {
        Optional<AtomRecord> record = atomSpi.read(key);
        if (record.isEmpty()) {
          throw new AtomExpiredException(key);
        }

        String currentToken = record.get().token();
        String lastSeenToken = lastSeen != null ? lastSeen.token() : null;

        if (!currentToken.equals(lastSeenToken)) {
          return Optional.of(new Snapshot<>(codec.decode(record.get().value()), currentToken));
        }

        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
          return Optional.empty();
        }

        try {
          if (semaphore.tryAcquire(remaining.toMillis(), TimeUnit.MILLISECONDS)) {
            semaphore.drainPermits();
          } else {
            return Optional.empty();
          }
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          return Optional.empty();
        }
      }
    } finally {
      subscription.cancel();
    }
  }

  @Override
  public void delete() {
    atomSpi.delete(key);
  }

  @Override
  public String key() {
    return key;
  }

  static String token(byte[] encodedBytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedBytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
