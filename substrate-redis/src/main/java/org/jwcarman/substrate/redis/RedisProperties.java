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
package org.jwcarman.substrate.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "substrate.redis")
public record RedisProperties(
    JournalProperties journal,
    MailboxProperties mailbox,
    NotifierProperties notifier,
    AtomProperties atom) {
  public record JournalProperties(
      boolean enabled, String prefix, long maxLen, Duration defaultTtl) {}

  public record MailboxProperties(boolean enabled, String prefix) {}

  public record NotifierProperties(boolean enabled, String channel) {}

  public record AtomProperties(boolean enabled, String prefix) {}
}
