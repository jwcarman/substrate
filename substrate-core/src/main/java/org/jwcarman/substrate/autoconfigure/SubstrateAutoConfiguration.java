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
package org.jwcarman.substrate.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.memory.InMemoryJournal;
import org.jwcarman.substrate.memory.InMemoryMailbox;
import org.jwcarman.substrate.memory.InMemoryNotifier;
import org.jwcarman.substrate.spi.Journal;
import org.jwcarman.substrate.spi.Mailbox;
import org.jwcarman.substrate.spi.Notifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

@AutoConfiguration
@EnableConfigurationProperties(SubstrateProperties.class)
@PropertySource("classpath:substrate-defaults.properties")
public class SubstrateAutoConfiguration {

  private static final Log log = LogFactory.getLog(SubstrateAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(Journal.class)
  public InMemoryJournal journal() {
    log.warn(
        "No Journal implementation found; using in-memory fallback (single-node only). "
            + "For clustered deployments, add a backend module (e.g. substrate-journal-redis).");
    return new InMemoryJournal();
  }

  @Bean
  @ConditionalOnMissingBean(Mailbox.class)
  public InMemoryMailbox mailbox() {
    log.warn(
        "No Mailbox implementation found; using in-memory fallback (single-node only). "
            + "For clustered deployments, add a backend module (e.g. substrate-mailbox-redis).");
    return new InMemoryMailbox();
  }

  @Bean
  @ConditionalOnMissingBean(Notifier.class)
  public InMemoryNotifier notifier() {
    log.warn(
        "No Notifier implementation found; using in-memory fallback (single-node only). "
            + "For clustered deployments, add a backend module (e.g. substrate-notifier-redis).");
    return new InMemoryNotifier();
  }
}
