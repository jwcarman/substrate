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
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.atom.Snapshot;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.subscription.CoalescingHandoff;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscriberBuilder;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscription;
import org.jwcarman.substrate.core.subscription.FeederSupport;

public class DefaultAtom<T> implements Atom<T> {

  private final AtomSpi atomSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;
  private final Duration maxTtl;
  private final ShutdownCoordinator shutdownCoordinator;

  public DefaultAtom(
      AtomSpi atomSpi,
      String key,
      Codec<T> codec,
      NotifierSpi notifier,
      Duration maxTtl,
      ShutdownCoordinator shutdownCoordinator) {
    this.atomSpi = atomSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
    this.maxTtl = maxTtl;
    this.shutdownCoordinator = shutdownCoordinator;
  }

  /**
   * Test-friendly convenience constructor that creates a private, throwaway {@link
   * ShutdownCoordinator}. Production code should always use the full constructor above with the
   * Spring-managed coordinator bean.
   */
  public DefaultAtom(
      AtomSpi atomSpi, String key, Codec<T> codec, NotifierSpi notifier, Duration maxTtl) {
    this(atomSpi, key, codec, notifier, maxTtl, new ShutdownCoordinator());
  }

  @Override
  public void set(T data, Duration ttl) {
    validateTtl(ttl);
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
    validateTtl(ttl);
    return atomSpi.touch(key, ttl);
  }

  @Override
  public Snapshot<T> get() {
    RawAtom raw = atomSpi.read(key).orElseThrow(() -> new AtomExpiredException(key));
    return new Snapshot<>(codec.decode(raw.value()), raw.token());
  }

  @Override
  public void delete() {
    atomSpi.delete(key);
    notifier.notify(key, "__DELETED__");
  }

  @Override
  public BlockingSubscription<Snapshot<T>> subscribe() {
    return buildBlockingSubscription(null);
  }

  @Override
  public BlockingSubscription<Snapshot<T>> subscribe(Snapshot<T> lastSeen) {
    return buildBlockingSubscription(lastSeen);
  }

  @Override
  public CallbackSubscription subscribe(Consumer<Snapshot<T>> onNext) {
    return buildCallbackSubscription(null, onNext, null);
  }

  @Override
  public CallbackSubscription subscribe(
      Consumer<Snapshot<T>> onNext, Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer) {
    return buildCallbackSubscription(null, onNext, customizer);
  }

  @Override
  public CallbackSubscription subscribe(Snapshot<T> lastSeen, Consumer<Snapshot<T>> onNext) {
    return buildCallbackSubscription(lastSeen, onNext, null);
  }

  @Override
  public CallbackSubscription subscribe(
      Snapshot<T> lastSeen,
      Consumer<Snapshot<T>> onNext,
      Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer) {
    return buildCallbackSubscription(lastSeen, onNext, customizer);
  }

  @Override
  public String key() {
    return key;
  }

  private BlockingSubscription<Snapshot<T>> buildBlockingSubscription(Snapshot<T> lastSeen) {
    CoalescingHandoff<Snapshot<T>> handoff = new CoalescingHandoff<>();
    Runnable canceller = startFeeder(handoff, lastSeen);
    return new DefaultBlockingSubscription<>(handoff, canceller, shutdownCoordinator);
  }

  private CallbackSubscription buildCallbackSubscription(
      Snapshot<T> lastSeen,
      Consumer<Snapshot<T>> onNext,
      Consumer<CallbackSubscriberBuilder<Snapshot<T>>> customizer) {
    CoalescingHandoff<Snapshot<T>> handoff = new CoalescingHandoff<>();
    Runnable canceller = startFeeder(handoff, lastSeen);

    DefaultCallbackSubscriberBuilder<Snapshot<T>> builder =
        new DefaultCallbackSubscriberBuilder<>();
    if (customizer != null) {
      customizer.accept(builder);
    }

    DefaultBlockingSubscription<Snapshot<T>> source =
        new DefaultBlockingSubscription<>(handoff, canceller, shutdownCoordinator);
    return new DefaultCallbackSubscription<>(source, onNext, builder.callbacks());
  }

  private Runnable startFeeder(CoalescingHandoff<Snapshot<T>> handoff, Snapshot<T> lastSeen) {
    AtomicReference<String> lastToken =
        new AtomicReference<>(lastSeen != null ? lastSeen.token() : null);

    return FeederSupport.start(
        key,
        notifier,
        handoff,
        "substrate-atom-feeder",
        () -> {
          Optional<RawAtom> raw = atomSpi.read(key);
          if (raw.isEmpty()) {
            handoff.markExpired();
            return false;
          }
          String currentToken = raw.get().token();
          if (!currentToken.equals(lastToken.get())) {
            Snapshot<T> snap = new Snapshot<>(codec.decode(raw.get().value()), currentToken);
            handoff.push(snap);
            lastToken.set(currentToken);
          }
          return true;
        });
  }

  static String token(byte[] encodedBytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedBytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private void validateTtl(Duration ttl) {
    if (ttl.compareTo(maxTtl) > 0) {
      throw new IllegalArgumentException(
          "Atom TTL " + ttl + " exceeds configured maximum " + maxTtl);
    }
  }
}
