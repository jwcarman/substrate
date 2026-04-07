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

import org.jwcarman.substrate.spi.Mailbox;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class JacksonMailboxFactory {

  private final Mailbox mailbox;
  private final ObjectMapper objectMapper;

  public JacksonMailboxFactory(Mailbox mailbox, ObjectMapper objectMapper) {
    this.mailbox = mailbox;
    this.objectMapper = objectMapper;
  }

  public <T> JacksonMailbox<T> create(Class<T> type) {
    return new JacksonMailbox<>(mailbox, objectMapper, type);
  }

  public <T> JacksonMailbox<T> create(TypeReference<T> typeRef) {
    return new JacksonMailbox<>(mailbox, objectMapper, objectMapper.constructType(typeRef));
  }
}
