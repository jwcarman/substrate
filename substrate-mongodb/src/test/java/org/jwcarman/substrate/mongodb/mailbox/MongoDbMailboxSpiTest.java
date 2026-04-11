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
package org.jwcarman.substrate.mongodb.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class MongoDbMailboxSpiTest {

  @Mock private MongoTemplate mongoTemplate;

  private MongoDbMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox = new MongoDbMailboxSpi(mongoTemplate, "substrate:mailbox:", "substrate_mailbox");
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }

  @Test
  void deliverUpdatesDocument() {
    com.mongodb.client.result.UpdateResult updateResult =
        com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("substrate_mailbox")))
        .thenReturn(updateResult);

    mailbox.deliver("substrate:mailbox:test", "hello".getBytes(StandardCharsets.UTF_8));

    verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq("substrate_mailbox"));
  }

  @Test
  void deliverThrowsExpiredWhenMailboxMissing() {
    com.mongodb.client.result.UpdateResult noMatch =
        com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("substrate_mailbox")))
        .thenReturn(noMatch);
    when(mongoTemplate.exists(any(Query.class), eq("substrate_mailbox"))).thenReturn(false);

    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        MailboxExpiredException.class, () -> mailbox.deliver("substrate:mailbox:test", data));
  }

  @Test
  void deliverThrowsFullWhenAlreadyDelivered() {
    com.mongodb.client.result.UpdateResult noMatch =
        com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("substrate_mailbox")))
        .thenReturn(noMatch);
    when(mongoTemplate.exists(any(Query.class), eq("substrate_mailbox"))).thenReturn(true);

    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThrows(MailboxFullException.class, () -> mailbox.deliver("substrate:mailbox:test", data));
  }

  @Test
  void getReturnsValueWhenPresent() {
    Document doc = new Document();
    doc.put("key", "substrate:mailbox:test");
    doc.put("value", new Binary("existing-value".getBytes(StandardCharsets.UTF_8)));

    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("substrate_mailbox")))
        .thenReturn(doc);

    Optional<byte[]> result = mailbox.get("substrate:mailbox:test");

    assertThat(result).contains("existing-value".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getThrowsWhenAbsent() {
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("substrate_mailbox")))
        .thenReturn(null);

    assertThrows(MailboxExpiredException.class, () -> mailbox.get("substrate:mailbox:test"));
  }

  @Test
  void getReturnsEmptyWhenCreatedButNotDelivered() {
    Document doc = new Document();
    doc.put("key", "substrate:mailbox:test");

    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("substrate_mailbox")))
        .thenReturn(doc);

    Optional<byte[]> result = mailbox.get("substrate:mailbox:test");

    assertThat(result).isEmpty();
  }

  @Test
  void deleteRemovesDocument() {
    mailbox.delete("substrate:mailbox:test");

    verify(mongoTemplate).remove(any(Query.class), eq("substrate_mailbox"));
  }
}
