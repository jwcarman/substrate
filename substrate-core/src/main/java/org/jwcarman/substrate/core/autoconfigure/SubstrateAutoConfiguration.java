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
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomFactory;
import org.jwcarman.substrate.core.atom.AtomSpi;
import org.jwcarman.substrate.core.atom.DefaultAtomFactory;
import org.jwcarman.substrate.core.journal.DefaultJournalFactory;
import org.jwcarman.substrate.core.journal.JournalSpi;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.mailbox.DefaultMailboxFactory;
import org.jwcarman.substrate.core.mailbox.MailboxSpi;
import org.jwcarman.substrate.core.memory.atom.InMemoryAtomSpi;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.sweep.Sweeper;
import org.jwcarman.substrate.journal.Journal;
import org.jwcarman.substrate.journal.JournalFactory;
import org.jwcarman.substrate.mailbox.Mailbox;
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

  private static final String FALLBACK_WARNING_SUFFIX =
      " implementation found; using in-memory fallback (single-node only). "
          + "For clustered deployments, add a backend module (e.g. substrate-redis).";

  private static String fallbackWarning(String primitive) {
    return "No " + primitive + FALLBACK_WARNING_SUFFIX;
  }

  @Bean
  @ConditionalOnMissingBean(JournalSpi.class)
  public InMemoryJournalSpi journalSpi() {
    log.warn(fallbackWarning("Journal"));
    return new InMemoryJournalSpi();
  }

  @Bean
  @ConditionalOnMissingBean(MailboxSpi.class)
  public InMemoryMailboxSpi mailboxSpi() {
    log.warn(fallbackWarning("Mailbox"));
    return new InMemoryMailboxSpi();
  }

  @Bean
  @ConditionalOnMissingBean(NotifierSpi.class)
  public InMemoryNotifier notifier() {
    log.warn(fallbackWarning("Notifier"));
    return new InMemoryNotifier();
  }

  @Bean
  @ConditionalOnMissingBean(AtomSpi.class)
  public InMemoryAtomSpi atomSpi() {
    log.warn(fallbackWarning("Atom"));
    return new InMemoryAtomSpi();
  }

  @Bean
  public ShutdownCoordinator shutdownCoordinator() {
    return new ShutdownCoordinator();
  }

  @Bean
  @ConditionalOnBean({AtomSpi.class, CodecFactory.class, NotifierSpi.class})
  public AtomFactory atomFactory(
      AtomSpi atomSpi,
      CodecFactory codecFactory,
      NotifierSpi notifier,
      SubstrateProperties properties,
      ShutdownCoordinator shutdownCoordinator) {
    return new DefaultAtomFactory(
        atomSpi, codecFactory, notifier, properties.atom().maxTtl(), shutdownCoordinator);
  }

  @Bean
  @ConditionalOnBean({JournalSpi.class, CodecFactory.class, NotifierSpi.class})
  public JournalFactory journalFactory(
      JournalSpi journalSpi,
      CodecFactory codecFactory,
      NotifierSpi notifier,
      SubstrateProperties properties,
      ShutdownCoordinator shutdownCoordinator) {
    var jp = properties.journal();
    return new DefaultJournalFactory(
        journalSpi,
        codecFactory,
        notifier,
        jp.subscription().queueCapacity(),
        jp.maxInactivityTtl(),
        jp.maxEntryTtl(),
        jp.maxRetentionTtl(),
        shutdownCoordinator);
  }

  @Bean
  @ConditionalOnBean({MailboxSpi.class, CodecFactory.class, NotifierSpi.class})
  public MailboxFactory mailboxFactory(
      MailboxSpi mailboxSpi,
      CodecFactory codecFactory,
      NotifierSpi notifier,
      SubstrateProperties properties,
      ShutdownCoordinator shutdownCoordinator) {
    return new DefaultMailboxFactory(
        mailboxSpi, codecFactory, notifier, properties.mailbox().maxTtl(), shutdownCoordinator);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(AtomSpi.class)
  @ConditionalOnProperty(
      prefix = "substrate.atom.sweep",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public Sweeper atomSweeper(AtomSpi atomSpi, SubstrateProperties props) {
    var sweep = props.atom().sweep();
    return new Sweeper(Atom.class, atomSpi, sweep.interval(), sweep.batchSize());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(JournalSpi.class)
  @ConditionalOnProperty(
      prefix = "substrate.journal.sweep",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public Sweeper journalSweeper(JournalSpi journalSpi, SubstrateProperties props) {
    var sweep = props.journal().sweep();
    return new Sweeper(Journal.class, journalSpi, sweep.interval(), sweep.batchSize());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(MailboxSpi.class)
  @ConditionalOnProperty(
      prefix = "substrate.mailbox.sweep",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public Sweeper mailboxSweeper(MailboxSpi mailboxSpi, SubstrateProperties props) {
    var sweep = props.mailbox().sweep();
    return new Sweeper(Mailbox.class, mailboxSpi, sweep.interval(), sweep.batchSize());
  }
}
