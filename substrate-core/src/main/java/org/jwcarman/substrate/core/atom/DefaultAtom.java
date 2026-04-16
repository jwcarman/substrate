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
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.Subscriber;
import org.jwcarman.substrate.SubscriberConfig;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.atom.AtomNotFoundException;
import org.jwcarman.substrate.atom.Snapshot;
import org.jwcarman.substrate.core.subscription.CallbackPumpSubscription;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;
import org.jwcarman.substrate.core.subscription.DefaultSubscriberBuilder;
import org.jwcarman.substrate.core.subscription.FeederSupport;
import org.jwcarman.substrate.core.subscription.SingleSlotHandoff;

public class DefaultAtom<T> implements Atom<T> {

  private final AtomContext context;
  private final String key;
  private final Codec<T> codec;
  private final AtomicBoolean connected;

  public DefaultAtom(AtomContext context, String key, Codec<T> codec, boolean connected) {
    this.context = context;
    this.key = key;
    this.codec = codec;
    this.connected = new AtomicBoolean(connected);
  }

  private void ensureExists() {
    if (connected.compareAndSet(true, false) && !context.spi().exists(key)) {
      throw new AtomNotFoundException(key);
    }
  }

  @Override
  public void set(T data, Duration ttl) {
    ensureExists();
    validateTtl(ttl);
    byte[] bytes = codec.encode(data);
    String newToken = token(bytes);
    boolean alive = context.spi().set(key, context.transformer().encode(bytes), newToken, ttl);
    if (!alive) {
      throw new AtomExpiredException(key);
    }
    context.notifier().notifyAtomChanged(key);
  }

  @Override
  public boolean touch(Duration ttl) {
    ensureExists();
    validateTtl(ttl);
    return context.spi().touch(key, ttl);
  }

  @Override
  public Snapshot<T> get() {
    ensureExists();
    RawAtom raw = context.spi().read(key).orElseThrow(() -> new AtomExpiredException(key));
    return new Snapshot<>(codec.decode(context.transformer().decode(raw.value())), raw.token());
  }

  @Override
  public void delete() {
    // delete() is idempotent — no existence probe, even for connected handles
    context.spi().delete(key);
    context.notifier().notifyAtomDeleted(key);
  }

  @Override
  public BlockingSubscription<Snapshot<T>> subscribe() {
    ensureExists();
    return buildBlockingSubscription(null);
  }

  @Override
  public BlockingSubscription<Snapshot<T>> subscribe(Snapshot<T> lastSeen) {
    ensureExists();
    return buildBlockingSubscription(lastSeen);
  }

  @Override
  public Subscription subscribe(Subscriber<Snapshot<T>> subscriber) {
    ensureExists();
    return buildCallbackPumpSubscription(null, subscriber);
  }

  @Override
  public Subscription subscribe(Consumer<SubscriberConfig<Snapshot<T>>> customizer) {
    ensureExists();
    return subscribe(DefaultSubscriberBuilder.from(customizer));
  }

  @Override
  public Subscription subscribe(Snapshot<T> lastSeen, Subscriber<Snapshot<T>> subscriber) {
    ensureExists();
    return buildCallbackPumpSubscription(lastSeen, subscriber);
  }

  @Override
  public Subscription subscribe(
      Snapshot<T> lastSeen, Consumer<SubscriberConfig<Snapshot<T>>> customizer) {
    ensureExists();
    return subscribe(lastSeen, DefaultSubscriberBuilder.from(customizer));
  }

  @Override
  public String key() {
    return key;
  }

  private BlockingSubscription<Snapshot<T>> buildBlockingSubscription(Snapshot<T> lastSeen) {
    SingleSlotHandoff<Snapshot<T>> handoff = new SingleSlotHandoff<>();
    Runnable canceller = startFeeder(handoff, lastSeen);
    return new DefaultBlockingSubscription<>(handoff, canceller, context.shutdownCoordinator());
  }

  private Subscription buildCallbackPumpSubscription(
      Snapshot<T> lastSeen, Subscriber<Snapshot<T>> subscriber) {
    SingleSlotHandoff<Snapshot<T>> handoff = new SingleSlotHandoff<>();
    Runnable canceller = startFeeder(handoff, lastSeen);
    var source =
        new DefaultBlockingSubscription<>(handoff, canceller, context.shutdownCoordinator());
    return new CallbackPumpSubscription<>(source, subscriber);
  }

  private Runnable startFeeder(SingleSlotHandoff<Snapshot<T>> handoff, Snapshot<T> lastSeen) {
    AtomicReference<String> lastToken =
        new AtomicReference<>(lastSeen != null ? lastSeen.token() : null);

    return FeederSupport.start(
        key,
        context.notifier()::subscribeToAtom,
        handoff,
        "substrate-atom-feeder",
        () -> {
          Optional<RawAtom> raw = context.spi().read(key);
          if (raw.isEmpty()) {
            handoff.markExpired();
            return false;
          }
          String currentToken = raw.get().token();
          if (!currentToken.equals(lastToken.get())) {
            Snapshot<T> snap =
                new Snapshot<>(
                    codec.decode(context.transformer().decode(raw.get().value())), currentToken);
            handoff.deliver(snap);
            lastToken.set(currentToken);
          }
          return true;
        });
  }

  private static final int TOKEN_BYTES = 16;

  /**
   * Hashes the encoded atom value into a compact staleness token for change detection. Uses the
   * first 128 bits of SHA-256 — plenty for pairwise collision resistance when comparing "last
   * observed value" against "current value," and the truncation halves the token length over the
   * full digest (22 chars of Base64URL instead of 43).
   */
  static String token(byte[] encodedBytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedBytes);
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(Arrays.copyOf(digest, TOKEN_BYTES));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private void validateTtl(Duration ttl) {
    if (ttl.compareTo(context.maxTtl()) > 0) {
      throw new IllegalArgumentException(
          "Atom TTL " + ttl + " exceeds configured maximum " + context.maxTtl());
    }
  }
}
