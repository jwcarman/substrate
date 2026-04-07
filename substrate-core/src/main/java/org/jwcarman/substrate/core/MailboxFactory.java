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

import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.spi.MailboxSpi;
import org.jwcarman.substrate.spi.Notifier;

public class MailboxFactory {

  private final MailboxSpi mailboxSpi;
  private final CodecFactory codecFactory;
  private final Notifier notifier;

  public MailboxFactory(MailboxSpi mailboxSpi, CodecFactory codecFactory, Notifier notifier) {
    this.mailboxSpi = mailboxSpi;
    this.codecFactory = codecFactory;
    this.notifier = notifier;
  }

  public <T> Mailbox<T> create(String name, Class<T> type) {
    return new DefaultMailbox<>(
        mailboxSpi, mailboxSpi.mailboxKey(name), codecFactory.create(type), notifier);
  }

  public <T> Mailbox<T> create(String name, TypeRef<T> typeRef) {
    return new DefaultMailbox<>(
        mailboxSpi, mailboxSpi.mailboxKey(name), codecFactory.create(typeRef), notifier);
  }
}
