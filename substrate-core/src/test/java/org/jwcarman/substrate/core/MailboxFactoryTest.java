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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.memory.InMemoryMailboxSpi;

class MailboxFactoryTest {

  @Test
  void createReturnsBoundMailboxWithPrefixedKey() {
    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    MailboxFactory factory = new MailboxFactory(spi);

    Mailbox mailbox = factory.create("my-elicit");

    assertEquals("substrate:mailbox:my-elicit", mailbox.key());
  }

  @Test
  void createdMailboxDelegatesToSpi() {
    InMemoryMailboxSpi spi = new InMemoryMailboxSpi();
    MailboxFactory factory = new MailboxFactory(spi);

    Mailbox mailbox = factory.create("test");
    mailbox.deliver("hello");

    assertEquals("hello", mailbox.await(Duration.ofSeconds(1)).join());
  }
}
