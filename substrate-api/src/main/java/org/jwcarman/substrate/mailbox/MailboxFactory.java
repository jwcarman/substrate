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
package org.jwcarman.substrate.mailbox;

import java.time.Duration;
import org.jwcarman.codec.spi.TypeRef;

public interface MailboxFactory {

  <T> Mailbox<T> create(String name, Class<T> type, Duration ttl);

  <T> Mailbox<T> create(String name, TypeRef<T> typeRef, Duration ttl);
}
