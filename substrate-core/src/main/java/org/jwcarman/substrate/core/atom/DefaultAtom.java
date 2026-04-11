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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomExpiredException;
import org.jwcarman.substrate.atom.Snapshot;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.jwcarman.substrate.core.subscription.CoalescingHandoff;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscriberBuilder;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscription;

public class DefaultAtom<T> implements Atom<T> {

  private static final String DELETED_PAYLOAD = "__DELETED__";

  private final AtomSpi atomSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;
  private final Duration maxTtl;

  public DefaultAtom(
      AtomSpi atomSpi, String key, Codec<T> codec, NotifierSpi notifier, Duration maxTtl) {
    this.atomSpi = atomSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
    this.maxTtl = maxTtl;
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
    notifier.notify(key, DELETED_PAYLOAD);
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
    return new DefaultBlockingSubscription<>(handoff, canceller);
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

    return new DefaultCallbackSubscription<>(
        handoff,
        canceller,
        onNext,
        builder.errorHandler(),
        builder.expirationHandler(),
        builder.deleteHandler(),
        builder.completeHandler());
  }

  private Runnable startFeeder(CoalescingHandoff<Snapshot<T>> handoff, Snapshot<T> lastSeen) {
    AtomicBoolean running = new AtomicBoolean(true);
    Semaphore semaphore = new Semaphore(0);
    AtomicReference<String> lastToken =
        new AtomicReference<>(lastSeen != null ? lastSeen.token() : null);

    NotifierSubscription notifierSub =
        notifier.subscribe(
            (notifiedKey, payload) ->
                handleNotification(notifiedKey, payload, handoff, running, semaphore));

    Thread feederThread =
        Thread.ofVirtual()
            .name("substrate-atom-feeder", 0)
            .start(() -> runFeederLoop(handoff, running, semaphore, lastToken, notifierSub));

    return () -> {
      running.set(false);
      feederThread.interrupt();
      notifierSub.cancel();
    };
  }

  private void handleNotification(
      String notifiedKey,
      String payload,
      CoalescingHandoff<Snapshot<T>> handoff,
      AtomicBoolean running,
      Semaphore semaphore) {
    if (!key.equals(notifiedKey)) {
      return;
    }
    if (DELETED_PAYLOAD.equals(payload)) {
      handoff.markDeleted();
      running.set(false);
    }
    semaphore.release();
  }

  private void runFeederLoop(
      CoalescingHandoff<Snapshot<T>> handoff,
      AtomicBoolean running,
      Semaphore semaphore,
      AtomicReference<String> lastToken,
      NotifierSubscription notifierSub) {
    try {
      while (running.get() && !Thread.currentThread().isInterrupted()) {
        if (!readAndPushIfChanged(handoff, lastToken)) {
          return;
        }
        waitForNudge(semaphore);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      handoff.error(e);
    } finally {
      notifierSub.cancel();
    }
  }

  private boolean readAndPushIfChanged(
      CoalescingHandoff<Snapshot<T>> handoff, AtomicReference<String> lastToken) {
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
  }

  private static void waitForNudge(Semaphore semaphore) throws InterruptedException {
    if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
      semaphore.drainPermits();
    }
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
