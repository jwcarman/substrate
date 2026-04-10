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
package org.jwcarman.substrate.core.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "substrate")
public record SubstrateProperties(
    AtomProperties atom, JournalProperties journal, MailboxProperties mailbox) {

  public SubstrateProperties {
    if (atom == null) atom = new AtomProperties(Duration.ofHours(24));
    if (journal == null) journal = new JournalProperties(Duration.ofDays(7));
    if (mailbox == null) mailbox = new MailboxProperties(Duration.ofMinutes(30));
  }

  public record AtomProperties(Duration maxTtl) {
    public AtomProperties {
      if (maxTtl == null) maxTtl = Duration.ofHours(24);
    }
  }

  public record JournalProperties(Duration maxTtl) {
    public JournalProperties {
      if (maxTtl == null) maxTtl = Duration.ofDays(7);
    }
  }

  public record MailboxProperties(Duration maxTtl) {
    public MailboxProperties {
      if (maxTtl == null) maxTtl = Duration.ofMinutes(30);
    }
  }
}
