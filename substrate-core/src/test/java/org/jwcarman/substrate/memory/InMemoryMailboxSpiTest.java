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
package org.jwcarman.substrate.memory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryMailboxSpiTest {

  private static final String KEY = "substrate:mailbox:test";

  private InMemoryMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox = new InMemoryMailboxSpi();
  }

  @Test
  void deliverThenGetReturnsValue() {
    mailbox.deliver(KEY, "hello".getBytes(UTF_8));

    Optional<byte[]> result = mailbox.get(KEY);

    assertTrue(result.isPresent());
    assertArrayEquals("hello".getBytes(UTF_8), result.get());
  }

  @Test
  void getReturnsEmptyWhenNotDelivered() {
    Optional<byte[]> result = mailbox.get(KEY);

    assertTrue(result.isEmpty());
  }

  @Test
  void deleteRemovesDeliveredValue() {
    mailbox.deliver(KEY, "hello".getBytes(UTF_8));

    mailbox.delete(KEY);

    assertTrue(mailbox.get(KEY).isEmpty());
  }

  @Test
  void mailboxKeyAppliesPrefix() {
    assertEquals("substrate:mailbox:my-key", mailbox.mailboxKey("my-key"));
  }
}
