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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.Subscriber;
import org.jwcarman.substrate.SubscriberConfig;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.subscription.CallbackPumpSubscription;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;
import org.jwcarman.substrate.core.subscription.DefaultSubscriberBuilder;
import org.jwcarman.substrate.core.subscription.FeederSupport;
import org.jwcarman.substrate.core.subscription.SingleSlotHandoff;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.jwcarman.substrate.mailbox.Mailbox;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxNotFoundException;

public class DefaultMailbox<T> implements Mailbox<T> {

  private final MailboxSpi mailboxSpi;
  private final String key;
  private final Codec<T> codec;
  private final PayloadTransformer transformer;
  private final Notifier notifier;
  private final ShutdownCoordinator shutdownCoordinator;
  private final AtomicBoolean connected;

  public DefaultMailbox(
      MailboxSpi mailboxSpi,
      String key,
      Codec<T> codec,
      PayloadTransformer transformer,
      Notifier notifier,
      ShutdownCoordinator shutdownCoordinator) {
    this(mailboxSpi, key, codec, transformer, notifier, shutdownCoordinator, false);
  }

  public DefaultMailbox(
      MailboxSpi mailboxSpi,
      String key,
      Codec<T> codec,
      PayloadTransformer transformer,
      Notifier notifier,
      ShutdownCoordinator shutdownCoordinator,
      boolean connected) {
    this.mailboxSpi = mailboxSpi;
    this.key = key;
    this.codec = codec;
    this.transformer = transformer;
    this.notifier = notifier;
    this.shutdownCoordinator = shutdownCoordinator;
    this.connected = new AtomicBoolean(connected);
  }

  private void ensureExists() {
    if (connected.compareAndSet(true, false) && !mailboxSpi.exists(key)) {
      throw new MailboxNotFoundException(key);
    }
  }

  @Override
  public void deliver(T value) {
    ensureExists();
    mailboxSpi.deliver(key, transformer.encode(codec.encode(value)));
    notifier.notifyMailboxChanged(key);
  }

  @Override
  public void delete() {
    // delete() is idempotent — no existence probe, even for connected handles
    mailboxSpi.delete(key);
    notifier.notifyMailboxDeleted(key);
  }

  @Override
  public BlockingSubscription<T> subscribe() {
    ensureExists();
    SingleSlotHandoff<T> handoff = new SingleSlotHandoff<>();
    Runnable canceller = startFeeder(handoff);
    return new DefaultBlockingSubscription<>(handoff, canceller, shutdownCoordinator);
  }

  @Override
  public Subscription subscribe(Subscriber<T> subscriber) {
    ensureExists();
    SingleSlotHandoff<T> handoff = new SingleSlotHandoff<>();
    Runnable canceller = startFeeder(handoff);
    var source = new DefaultBlockingSubscription<>(handoff, canceller, shutdownCoordinator);
    return new CallbackPumpSubscription<>(source, subscriber);
  }

  @Override
  public Subscription subscribe(Consumer<SubscriberConfig<T>> customizer) {
    ensureExists();
    return subscribe(DefaultSubscriberBuilder.from(customizer));
  }

  private Runnable startFeeder(SingleSlotHandoff<T> handoff) {
    return FeederSupport.start(
        key,
        notifier::subscribeToMailbox,
        handoff,
        "substrate-mailbox-feeder",
        () -> {
          try {
            Optional<byte[]> value = mailboxSpi.get(key);
            if (value.isPresent()) {
              handoff.deliver(codec.decode(transformer.decode(value.get())));
              // Mailbox semantics: exactly one delivery, then complete.
              handoff.markCompleted();
              return false;
            }
            return true;
          } catch (MailboxExpiredException _) {
            handoff.markExpired();
            return false;
          }
        });
  }

  @Override
  public String key() {
    return key;
  }
}
