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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.jwcarman.substrate.spi.MailboxSpi;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

public class JacksonMailbox<T> {

  private final MailboxSpi mailbox;
  private final ObjectMapper objectMapper;
  private final JavaType javaType;

  public JacksonMailbox(MailboxSpi mailbox, ObjectMapper objectMapper, JavaType javaType) {
    this.mailbox = mailbox;
    this.objectMapper = objectMapper;
    this.javaType = javaType;
  }

  public JacksonMailbox(MailboxSpi mailbox, ObjectMapper objectMapper, Class<T> type) {
    this(mailbox, objectMapper, objectMapper.constructType(type));
  }

  public void deliver(String key, T value) {
    mailbox.deliver(key, objectMapper.writeValueAsString(value));
  }

  public CompletableFuture<T> await(String key, Duration timeout) {
    return mailbox.await(key, timeout).thenApply(json -> objectMapper.readValue(json, javaType));
  }

  public void delete(String key) {
    mailbox.delete(key);
  }

  public String mailboxKey(String name) {
    return mailbox.mailboxKey(name);
  }
}
