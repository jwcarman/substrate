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
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.mailbox.Mailbox;
import org.jwcarman.substrate.mailbox.MailboxFactory;

public class DefaultMailboxFactory implements MailboxFactory {

  private final MailboxSpi mailboxSpi;
  private final CodecFactory codecFactory;
  private final NotifierSpi notifier;
  private final Duration maxTtl;

  public DefaultMailboxFactory(
      MailboxSpi mailboxSpi, CodecFactory codecFactory, NotifierSpi notifier, Duration maxTtl) {
    this.mailboxSpi = mailboxSpi;
    this.codecFactory = codecFactory;
    this.notifier = notifier;
    this.maxTtl = maxTtl;
  }

  @Override
  public <T> Mailbox<T> create(String name, Class<T> type, Duration ttl) {
    if (ttl.compareTo(maxTtl) > 0) {
      throw new IllegalArgumentException(
          "Mailbox TTL " + ttl + " exceeds configured maximum " + maxTtl);
    }
    String key = mailboxSpi.mailboxKey(name);
    mailboxSpi.create(key, ttl);
    return new DefaultMailbox<>(mailboxSpi, key, codecFactory.create(type), notifier);
  }

  @Override
  public <T> Mailbox<T> create(String name, TypeRef<T> typeRef, Duration ttl) {
    if (ttl.compareTo(maxTtl) > 0) {
      throw new IllegalArgumentException(
          "Mailbox TTL " + ttl + " exceeds configured maximum " + maxTtl);
    }
    String key = mailboxSpi.mailboxKey(name);
    mailboxSpi.create(key, ttl);
    return new DefaultMailbox<>(mailboxSpi, key, codecFactory.create(typeRef), notifier);
  }
}
