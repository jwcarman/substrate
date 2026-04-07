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
package org.jwcarman.substrate.core;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.spi.MailboxSpi;
import org.jwcarman.substrate.spi.Notifier;

public class DefaultMailbox<T> implements Mailbox<T> {

  private final MailboxSpi mailboxSpi;
  private final String key;
  private final Codec<T> codec;
  private final Notifier notifier;

  public DefaultMailbox(MailboxSpi mailboxSpi, String key, Codec<T> codec, Notifier notifier) {
    this.mailboxSpi = mailboxSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
  }

  @Override
  public void deliver(T value) {
    mailboxSpi.deliver(key, codec.encode(value));
    notifier.notify(key, "delivered");
  }

  @Override
  public CompletableFuture<T> await(Duration timeout) {
    // Check if value is already delivered
    Optional<byte[]> existing = mailboxSpi.get(key);
    if (existing.isPresent()) {
      return CompletableFuture.completedFuture(codec.decode(existing.get()));
    }

    CompletableFuture<T> future = new CompletableFuture<>();

    notifier.subscribe(
        (notifiedKey, payload) -> {
          if (key.equals(notifiedKey) && !future.isDone()) {
            mailboxSpi.get(key).ifPresent(bytes -> future.complete(codec.decode(bytes)));
          }
        });

    // Double-check in case deliver() was called between our get and subscribe
    mailboxSpi.get(key).ifPresent(bytes -> future.complete(codec.decode(bytes)));

    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void delete() {
    mailboxSpi.delete(key);
  }

  @Override
  public String key() {
    return key;
  }
}
