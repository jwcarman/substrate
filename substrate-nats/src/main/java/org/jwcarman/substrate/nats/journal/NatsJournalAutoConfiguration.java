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
package org.jwcarman.substrate.nats.journal;

import io.nats.client.Connection;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.journal.JournalSpi;
import org.jwcarman.substrate.nats.NatsAutoConfiguration;
import org.jwcarman.substrate.nats.NatsProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = NatsAutoConfiguration.class, before = SubstrateAutoConfiguration.class)
@ConditionalOnProperty(
    prefix = "substrate.nats.journal",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NatsJournalAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(JournalSpi.class)
  public NatsJournalSpi natsJournal(Connection connection, NatsProperties properties) {
    NatsProperties.JournalProperties journal = properties.journal();
    return new NatsJournalSpi(
        connection,
        journal.prefix(),
        journal.streamName(),
        journal.maxAge(),
        journal.maxMessages());
  }
}
