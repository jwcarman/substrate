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
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.jwcarman.substrate.mailbox.Mailbox;
import org.jwcarman.substrate.mailbox.MailboxFactory;

public class DefaultMailboxFactory implements MailboxFactory {

  private final CodecFactory codecFactory;
  private final Duration maxTtl;
  private final MailboxContext context;

  public DefaultMailboxFactory(
      MailboxSpi mailboxSpi,
      CodecFactory codecFactory,
      PayloadTransformer transformer,
      Notifier notifier,
      Duration maxTtl,
      ShutdownCoordinator shutdownCoordinator) {
    this.codecFactory = codecFactory;
    this.maxTtl = maxTtl;
    this.context = new MailboxContext(mailboxSpi, transformer, notifier, shutdownCoordinator);
  }

  @Override
  public <T> Mailbox<T> create(String name, Class<T> type, Duration ttl) {
    validateTtl(ttl);
    String key = context.spi().mailboxKey(name);
    context.spi().create(key, ttl);
    return new DefaultMailbox<>(context, key, codecFactory.create(type), false);
  }

  @Override
  public <T> Mailbox<T> create(String name, TypeRef<T> typeRef, Duration ttl) {
    validateTtl(ttl);
    String key = context.spi().mailboxKey(name);
    context.spi().create(key, ttl);
    return new DefaultMailbox<>(context, key, codecFactory.create(typeRef), false);
  }

  @Override
  public <T> Mailbox<T> connect(String name, Class<T> type) {
    String key = context.spi().mailboxKey(name);
    return new DefaultMailbox<>(context, key, codecFactory.create(type), true);
  }

  @Override
  public <T> Mailbox<T> connect(String name, TypeRef<T> typeRef) {
    String key = context.spi().mailboxKey(name);
    return new DefaultMailbox<>(context, key, codecFactory.create(typeRef), true);
  }

  private void validateTtl(Duration ttl) {
    if (ttl.compareTo(maxTtl) > 0) {
      throw new IllegalArgumentException(
          "Mailbox TTL " + ttl + " exceeds configured maximum " + maxTtl);
    }
  }
}
