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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.substrate.atom.AtomFactory;
import org.jwcarman.substrate.core.atom.AtomSpi;
import org.jwcarman.substrate.core.atom.AtomSweeper;
import org.jwcarman.substrate.core.atom.DefaultAtomFactory;
import org.jwcarman.substrate.core.journal.DefaultJournalFactory;
import org.jwcarman.substrate.core.journal.JournalSpi;
import org.jwcarman.substrate.core.journal.JournalSweeper;
import org.jwcarman.substrate.core.mailbox.DefaultMailboxFactory;
import org.jwcarman.substrate.core.mailbox.MailboxSpi;
import org.jwcarman.substrate.core.mailbox.MailboxSweeper;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.journal.JournalFactory;
import org.jwcarman.substrate.mailbox.MailboxFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

@AutoConfiguration
@EnableConfigurationProperties(SubstrateProperties.class)
@PropertySource("classpath:substrate-defaults.properties")
public class SubstrateAutoConfiguration {

  private static final Log log = LogFactory.getLog(SubstrateAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(JournalSpi.class)
  public InMemoryJournalSpi journalSpi() {
    log.warn(
        "No Journal implementation found; using in-memory fallback (single-node only). "
            + "For clustered deployments, add a backend module (e.g. substrate-journal-redis).");
    return new InMemoryJournalSpi();
  }

  @Bean
  @ConditionalOnMissingBean(MailboxSpi.class)
  public InMemoryMailboxSpi mailboxSpi() {
    log.warn(
        "No Mailbox implementation found; using in-memory fallback (single-node only). "
            + "For clustered deployments, add a backend module (e.g. substrate-mailbox-redis).");
    return new InMemoryMailboxSpi();
  }

  @Bean
  @ConditionalOnMissingBean(NotifierSpi.class)
  public InMemoryNotifier notifier() {
    log.warn(
        "No NotifierSpi implementation found; using in-memory fallback (single-node only). "
            + "For clustered deployments, add a backend module (e.g. substrate-notifier-redis).");
    return new InMemoryNotifier();
  }

  @Bean
  @ConditionalOnMissingBean(AtomSpi.class)
  public InMemoryAtomSpi atomSpi() {
    log.warn(
        "No Atom implementation found; using in-memory fallback "
            + "(single-node only). For clustered deployments, add a backend "
            + "module (e.g. substrate-atom-redis).");
    return new InMemoryAtomSpi();
  }

  @Bean
  @ConditionalOnBean({AtomSpi.class, CodecFactory.class, NotifierSpi.class})
  public AtomFactory atomFactory(
      AtomSpi atomSpi,
      CodecFactory codecFactory,
      NotifierSpi notifier,
      SubstrateProperties properties) {
    return new DefaultAtomFactory(atomSpi, codecFactory, notifier, properties.atom().maxTtl());
  }

  @Bean
  @ConditionalOnBean({JournalSpi.class, CodecFactory.class, NotifierSpi.class})
  public JournalFactory journalFactory(
      JournalSpi journalSpi,
      CodecFactory codecFactory,
      NotifierSpi notifier,
      SubstrateProperties properties) {
    var jp = properties.journal();
    return new DefaultJournalFactory(
        journalSpi,
        codecFactory,
        notifier,
        jp.maxInactivityTtl(),
        jp.maxEntryTtl(),
        jp.maxRetentionTtl());
  }

  @Bean
  @ConditionalOnBean({MailboxSpi.class, CodecFactory.class, NotifierSpi.class})
  public MailboxFactory mailboxFactory(
      MailboxSpi mailboxSpi,
      CodecFactory codecFactory,
      NotifierSpi notifier,
      SubstrateProperties properties) {
    return new DefaultMailboxFactory(
        mailboxSpi, codecFactory, notifier, properties.mailbox().maxTtl());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(AtomSpi.class)
  @ConditionalOnProperty(
      prefix = "substrate.atom.sweep",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public AtomSweeper atomSweeper(AtomSpi atomSpi, SubstrateProperties props) {
    var sweep = props.atom().sweep();
    return new AtomSweeper(atomSpi, sweep.interval(), sweep.batchSize());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(JournalSpi.class)
  @ConditionalOnProperty(
      prefix = "substrate.journal.sweep",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public JournalSweeper journalSweeper(JournalSpi journalSpi, SubstrateProperties props) {
    var sweep = props.journal().sweep();
    return new JournalSweeper(journalSpi, sweep.interval(), sweep.batchSize());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(MailboxSpi.class)
  @ConditionalOnProperty(
      prefix = "substrate.mailbox.sweep",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public MailboxSweeper mailboxSweeper(MailboxSpi mailboxSpi, SubstrateProperties props) {
    var sweep = props.mailbox().sweep();
    return new MailboxSweeper(mailboxSpi, sweep.interval(), sweep.batchSize());
  }
}
