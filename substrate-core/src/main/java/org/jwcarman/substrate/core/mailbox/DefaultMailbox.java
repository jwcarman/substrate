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

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscriberBuilder;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscription;
import org.jwcarman.substrate.core.subscription.SingleShotHandoff;
import org.jwcarman.substrate.mailbox.Mailbox;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

public class DefaultMailbox<T> implements Mailbox<T> {

  private static final String DELETED_PAYLOAD = "__DELETED__";

  private final MailboxSpi mailboxSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;

  public DefaultMailbox(MailboxSpi mailboxSpi, String key, Codec<T> codec, NotifierSpi notifier) {
    this.mailboxSpi = mailboxSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
  }

  @Override
  public void deliver(T value) {
    try {
      mailboxSpi.deliver(key, codec.encode(value));
    } catch (MailboxExpiredException | MailboxFullException e) {
      throw e;
    }
    notifier.notify(key, "delivered");
  }

  @Override
  public void delete() {
    mailboxSpi.delete(key);
    notifier.notify(key, DELETED_PAYLOAD);
  }

  @Override
  public BlockingSubscription<T> subscribe() {
    SingleShotHandoff<T> handoff = new SingleShotHandoff<>();
    Runnable canceller = startFeeder(handoff);
    return new DefaultBlockingSubscription<>(handoff, canceller);
  }

  @Override
  public CallbackSubscription subscribe(Consumer<T> onNext) {
    return subscribe(onNext, null);
  }

  @Override
  public CallbackSubscription subscribe(
      Consumer<T> onNext, Consumer<CallbackSubscriberBuilder<T>> customizer) {
    SingleShotHandoff<T> handoff = new SingleShotHandoff<>();
    Runnable canceller = startFeeder(handoff);

    DefaultCallbackSubscriberBuilder<T> builder = new DefaultCallbackSubscriberBuilder<>();
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

  private Runnable startFeeder(SingleShotHandoff<T> handoff) {
    AtomicBoolean running = new AtomicBoolean(true);
    Semaphore semaphore = new Semaphore(0);

    NotifierSubscription notifierSub =
        notifier.subscribe(
            (notifiedKey, payload) ->
                handleNotification(notifiedKey, payload, handoff, running, semaphore));

    Thread feederThread =
        Thread.ofVirtual()
            .name("substrate-mailbox-feeder", 0)
            .start(() -> runFeederLoop(handoff, running, semaphore, notifierSub));

    return () -> {
      running.set(false);
      feederThread.interrupt();
      notifierSub.cancel();
    };
  }

  private void handleNotification(
      String notifiedKey,
      String payload,
      SingleShotHandoff<T> handoff,
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
      SingleShotHandoff<T> handoff,
      AtomicBoolean running,
      Semaphore semaphore,
      NotifierSubscription notifierSub) {
    try {
      while (running.get() && !Thread.currentThread().isInterrupted()) {
        if (tryPushDelivery(handoff)) {
          return;
        }
        waitForNudge(semaphore);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (MailboxExpiredException e) {
      handoff.markExpired();
    } catch (RuntimeException e) {
      handoff.error(e);
    } finally {
      notifierSub.cancel();
    }
  }

  private boolean tryPushDelivery(SingleShotHandoff<T> handoff) {
    Optional<byte[]> value = mailboxSpi.get(key);
    if (value.isPresent()) {
      handoff.push(codec.decode(value.get()));
      return true;
    }
    return false;
  }

  private static void waitForNudge(Semaphore semaphore) throws InterruptedException {
    if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
      semaphore.drainPermits();
    }
  }

  @Override
  public String key() {
    return key;
  }
}
