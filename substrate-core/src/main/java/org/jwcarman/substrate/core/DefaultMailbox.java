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
import java.util.concurrent.CompletableFuture;
import org.jwcarman.substrate.spi.MailboxSpi;

public class DefaultMailbox implements Mailbox {

  private final MailboxSpi mailboxSpi;
  private final String key;

  public DefaultMailbox(MailboxSpi mailboxSpi, String key) {
    this.mailboxSpi = mailboxSpi;
    this.key = key;
  }

  @Override
  public void deliver(String value) {
    mailboxSpi.deliver(key, value);
  }

  @Override
  public CompletableFuture<String> await(Duration timeout) {
    return mailboxSpi.await(key, timeout);
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
