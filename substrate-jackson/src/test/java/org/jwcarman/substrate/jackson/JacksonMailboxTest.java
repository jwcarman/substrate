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
package org.jwcarman.substrate.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.memory.InMemoryMailbox;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class JacksonMailboxTest {

  private InMemoryMailbox mailbox;
  private JsonMapper objectMapper;

  @BeforeEach
  void setUp() {
    mailbox = new InMemoryMailbox();
    objectMapper = JsonMapper.builder().build();
  }

  @Test
  void roundTripsSimpleRecord() {
    JacksonMailbox<SimpleRecord> typed =
        new JacksonMailbox<>(mailbox, objectMapper, SimpleRecord.class);
    String key = typed.mailboxKey("simple");
    SimpleRecord original = new SimpleRecord("test", 99);

    CompletableFuture<SimpleRecord> future = typed.await(key, Duration.ofSeconds(5));
    typed.deliver(key, original);

    assertThat(future.join()).isEqualTo(original);
  }

  @Test
  void roundTripsGenericTypeViaTypeReference() {
    JacksonMailbox<List<String>> typed =
        new JacksonMailbox<>(
            mailbox,
            objectMapper,
            objectMapper.constructType(new TypeReference<List<String>>() {}));
    String key = typed.mailboxKey("generic");
    List<String> original = List.of("hello", "world");

    CompletableFuture<List<String>> future = typed.await(key, Duration.ofSeconds(5));
    typed.deliver(key, original);

    assertThat(future.join()).isEqualTo(original);
  }

  @Test
  void deleteDelegates() {
    JacksonMailbox<SimpleRecord> typed =
        new JacksonMailbox<>(mailbox, objectMapper, SimpleRecord.class);
    String key = typed.mailboxKey("delete");

    typed.delete(key);
    // No exception means delegation succeeded
  }

  @Test
  void mailboxKeyDelegates() {
    JacksonMailbox<SimpleRecord> typed =
        new JacksonMailbox<>(mailbox, objectMapper, SimpleRecord.class);
    String key = typed.mailboxKey("test-name");
    assertThat(key).isNotNull();
    assertThat(key).isEqualTo(mailbox.mailboxKey("test-name"));
  }

  record SimpleRecord(String name, int value) {}
}
