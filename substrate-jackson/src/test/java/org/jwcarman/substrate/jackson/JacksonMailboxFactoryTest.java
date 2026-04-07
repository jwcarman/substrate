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
import org.jwcarman.substrate.memory.InMemoryMailboxSpi;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class JacksonMailboxFactoryTest {

  private JacksonMailboxFactory factory;
  private InMemoryMailboxSpi mailbox;

  @BeforeEach
  void setUp() {
    mailbox = new InMemoryMailboxSpi();
    factory = new JacksonMailboxFactory(mailbox, JsonMapper.builder().build());
  }

  @Test
  void createsMailboxForClassType() {
    JacksonMailbox<TestMessage> typed = factory.create(TestMessage.class);
    String key = typed.mailboxKey("class-type");
    TestMessage original = new TestMessage("hello");

    CompletableFuture<TestMessage> future = typed.await(key, Duration.ofSeconds(5));
    typed.deliver(key, original);

    assertThat(future.join()).isEqualTo(original);
  }

  @Test
  void createsMailboxForTypeReference() {
    JacksonMailbox<List<String>> typed = factory.create(new TypeReference<List<String>>() {});
    String key = typed.mailboxKey("type-ref");
    List<String> original = List.of("a", "b", "c");

    CompletableFuture<List<String>> future = typed.await(key, Duration.ofSeconds(5));
    typed.deliver(key, original);

    assertThat(future.join()).isEqualTo(original);
  }

  record TestMessage(String text) {}
}
