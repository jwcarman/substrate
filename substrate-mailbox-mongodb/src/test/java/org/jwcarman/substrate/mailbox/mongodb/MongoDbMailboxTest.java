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
package org.jwcarman.substrate.mailbox.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.spi.Notifier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class MongoDbMailboxTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private Notifier notifier;

  private MongoDbMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox =
        new MongoDbMailboxSpi(
            mongoTemplate,
            notifier,
            "substrate:mailbox:",
            "substrate_mailbox",
            Duration.ofMinutes(5));
  }

  @Test
  void mailboxKeyUsesConfiguredPrefix() {
    assertThat(mailbox.mailboxKey("my-box")).isEqualTo("substrate:mailbox:my-box");
  }

  @Test
  void deliverUpsertsDocumentAndNotifies() {
    mailbox.deliver("substrate:mailbox:test", "hello".getBytes(StandardCharsets.UTF_8));

    verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq("substrate_mailbox"));
    verify(notifier).notify("substrate:mailbox:test", "substrate:mailbox:test");
  }

  @Test
  void awaitReturnsExistingValue() {
    Document doc = new Document();
    doc.put("key", "substrate:mailbox:test");
    doc.put("value", new Binary("existing-value".getBytes(StandardCharsets.UTF_8)));

    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("substrate_mailbox")))
        .thenReturn(doc);

    var future = mailbox.await("substrate:mailbox:test", Duration.ofSeconds(5));

    assertThat(future.join()).isEqualTo("existing-value".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void deleteRemovesDocument() {
    mailbox.delete("substrate:mailbox:test");

    verify(mongoTemplate).remove(any(Query.class), eq("substrate_mailbox"));
  }
}
