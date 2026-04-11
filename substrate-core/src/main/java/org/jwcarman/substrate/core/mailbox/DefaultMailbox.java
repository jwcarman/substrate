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
import java.util.function.Consumer;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscriberBuilder;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscription;
import org.jwcarman.substrate.core.subscription.FeederSupport;
import org.jwcarman.substrate.core.subscription.SingleShotHandoff;
import org.jwcarman.substrate.mailbox.Mailbox;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;

public class DefaultMailbox<T> implements Mailbox<T> {

  private final MailboxSpi mailboxSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;
  private final ShutdownCoordinator shutdownCoordinator;

  public DefaultMailbox(
      MailboxSpi mailboxSpi,
      String key,
      Codec<T> codec,
      NotifierSpi notifier,
      ShutdownCoordinator shutdownCoordinator) {
    this.mailboxSpi = mailboxSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
    this.shutdownCoordinator = shutdownCoordinator;
  }

  /** Test-friendly convenience constructor with a throwaway {@link ShutdownCoordinator}. */
  public DefaultMailbox(MailboxSpi mailboxSpi, String key, Codec<T> codec, NotifierSpi notifier) {
    this(mailboxSpi, key, codec, notifier, new ShutdownCoordinator());
  }

  @Override
  public void deliver(T value) {
    mailboxSpi.deliver(key, codec.encode(value));
    notifier.notify(key, "delivered");
  }

  @Override
  public void delete() {
    mailboxSpi.delete(key);
    notifier.notify(key, "__DELETED__");
  }

  @Override
  public BlockingSubscription<T> subscribe() {
    SingleShotHandoff<T> handoff = new SingleShotHandoff<>();
    Runnable canceller = startFeeder(handoff);
    return new DefaultBlockingSubscription<>(handoff, canceller, shutdownCoordinator);
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

    DefaultBlockingSubscription<T> source =
        new DefaultBlockingSubscription<>(handoff, canceller, shutdownCoordinator);
    return new DefaultCallbackSubscription<>(source, onNext, builder.callbacks());
  }

  private Runnable startFeeder(SingleShotHandoff<T> handoff) {
    return FeederSupport.start(
        key,
        notifier,
        handoff,
        "substrate-mailbox-feeder",
        () -> {
          try {
            Optional<byte[]> value = mailboxSpi.get(key);
            if (value.isPresent()) {
              handoff.push(codec.decode(value.get()));
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
