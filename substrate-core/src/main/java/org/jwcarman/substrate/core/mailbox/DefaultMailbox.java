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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.jwcarman.substrate.mailbox.Mailbox;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;

public class DefaultMailbox<T> implements Mailbox<T> {

  private static final Duration READER_POLL_TIMEOUT = Duration.ofSeconds(1);

  private final MailboxSpi mailboxSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;
  private final CompletableFuture<T> future = new CompletableFuture<>();
  private final Semaphore semaphore = new Semaphore(0);
  private final NotifierSubscription notifierSubscription;

  public DefaultMailbox(MailboxSpi mailboxSpi, String key, Codec<T> codec, NotifierSpi notifier) {
    this.mailboxSpi = mailboxSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;

    notifierSubscription =
        notifier.subscribe(
            (notifiedKey, payload) -> {
              if (key.equals(notifiedKey)) {
                semaphore.release();
              }
            });

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                while (!future.isDone()) {
                  try {
                    Optional<byte[]> value = mailboxSpi.get(key);
                    if (value.isPresent()) {
                      future.complete(codec.decode(value.get()));
                      return;
                    }
                  } catch (MailboxExpiredException e) {
                    future.completeExceptionally(e);
                    return;
                  }

                  if (semaphore.tryAcquire(READER_POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    semaphore.drainPermits();
                  }
                }
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            });
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
  public Optional<T> poll(Duration timeout) {
    try {
      T value = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return Optional.of(value);
    } catch (TimeoutException | java.util.concurrent.CancellationException _) {
      return Optional.empty();
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof MailboxExpiredException mee) {
        throw mee;
      }
      return Optional.empty();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  @Override
  public void delete() {
    future.cancel(false);
    notifierSubscription.cancel();
    semaphore.release();
    mailboxSpi.delete(key);
  }

  @Override
  public String key() {
    return key;
  }
}
