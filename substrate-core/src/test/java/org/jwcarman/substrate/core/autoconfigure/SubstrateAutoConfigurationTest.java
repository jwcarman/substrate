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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.substrate.core.atom.AtomSweeper;
import org.jwcarman.substrate.core.journal.JournalSpi;
import org.jwcarman.substrate.core.journal.JournalSweeper;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.jwcarman.substrate.core.mailbox.MailboxSpi;
import org.jwcarman.substrate.core.mailbox.MailboxSweeper;
import org.jwcarman.substrate.core.memory.journal.InMemoryJournalSpi;
import org.jwcarman.substrate.core.memory.mailbox.InMemoryMailboxSpi;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.NotificationHandler;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.jwcarman.substrate.journal.JournalFactory;
import org.jwcarman.substrate.mailbox.MailboxFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class SubstrateAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SubstrateAutoConfiguration.class));

  @Test
  void createsPropertiesBean() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(SubstrateProperties.class));
  }

  @Test
  void createsInMemoryJournalSpiWhenNoOtherBeanExists() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(JournalSpi.class);
          assertThat(context.getBean(JournalSpi.class)).isInstanceOf(InMemoryJournalSpi.class);
        });
  }

  @Test
  void createsInMemoryMailboxSpiWhenNoOtherBeanExists() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(MailboxSpi.class);
          assertThat(context.getBean(MailboxSpi.class)).isInstanceOf(InMemoryMailboxSpi.class);
        });
  }

  @Test
  void createsInMemoryNotifierWhenNoOtherBeanExists() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(NotifierSpi.class);
          assertThat(context.getBean(NotifierSpi.class)).isInstanceOf(InMemoryNotifier.class);
        });
  }

  @Test
  void doesNotCreateInMemoryJournalSpiWhenExternalBeanExists() {
    contextRunner
        .withUserConfiguration(CustomJournalSpiConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(JournalSpi.class);
              assertThat(context.getBean(JournalSpi.class)).isInstanceOf(StubJournalSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryJournalSpi.class);
            });
  }

  @Test
  void doesNotCreateInMemoryMailboxSpiWhenExternalBeanExists() {
    contextRunner
        .withUserConfiguration(CustomMailboxSpiConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MailboxSpi.class);
              assertThat(context.getBean(MailboxSpi.class)).isInstanceOf(StubMailboxSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryMailboxSpi.class);
            });
  }

  @Test
  void doesNotCreateInMemoryNotifierWhenExternalBeanExists() {
    contextRunner
        .withUserConfiguration(CustomNotifierConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NotifierSpi.class);
              assertThat(context.getBean(NotifierSpi.class)).isInstanceOf(StubNotifierSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
  }

  @Test
  void logsWarningsWhenFallingBackToInMemoryImplementations(CapturedOutput output) {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(InMemoryJournalSpi.class);
          assertThat(context).hasSingleBean(InMemoryMailboxSpi.class);
          assertThat(context).hasSingleBean(InMemoryNotifier.class);
        });
    assertThat(output)
        .contains("No Journal implementation found; using in-memory fallback")
        .contains("No Mailbox implementation found; using in-memory fallback")
        .contains("No NotifierSpi implementation found; using in-memory fallback");
  }

  @Test
  void doesNotLogWarningsWhenExternalBeansExist(CapturedOutput output) {
    contextRunner
        .withUserConfiguration(
            CustomJournalSpiConfiguration.class,
            CustomMailboxSpiConfiguration.class,
            CustomNotifierConfiguration.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(InMemoryJournalSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryMailboxSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
    assertThat(output)
        .doesNotContain("No Journal implementation found; using in-memory fallback")
        .doesNotContain("No Mailbox implementation found; using in-memory fallback")
        .doesNotContain("No NotifierSpi implementation found; using in-memory fallback");
  }

  @Test
  void createsJournalFactoryWhenJournalSpiAndCodecFactoryBeansExist() {
    contextRunner
        .withUserConfiguration(CodecFactoryConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(JournalFactory.class));
  }

  @Test
  void createsMailboxFactoryWhenMailboxSpiAndCodecFactoryBeansExist() {
    contextRunner
        .withUserConfiguration(CodecFactoryConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(MailboxFactory.class));
  }

  @Test
  void doesNotCreateJournalFactoryWhenCodecFactoryBeanIsMissing() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(JournalFactory.class));
  }

  @Test
  void doesNotCreateMailboxFactoryWhenCodecFactoryBeanIsMissing() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(MailboxFactory.class));
  }

  @Test
  void maxTtlConfigurationOverrideViaProperties() {
    contextRunner
        .withPropertyValues(
            "substrate.journal.max-entry-ttl=PT1H",
            "substrate.journal.max-inactivity-ttl=PT6H",
            "substrate.journal.max-retention-ttl=P7D",
            "substrate.mailbox.max-ttl=PT10M",
            "substrate.atom.max-ttl=PT2H")
        .run(
            context -> {
              SubstrateProperties props = context.getBean(SubstrateProperties.class);
              assertThat(props.journal().maxEntryTtl()).isEqualTo(Duration.ofHours(1));
              assertThat(props.journal().maxInactivityTtl()).isEqualTo(Duration.ofHours(6));
              assertThat(props.journal().maxRetentionTtl()).isEqualTo(Duration.ofDays(7));
              assertThat(props.mailbox().maxTtl()).isEqualTo(Duration.ofMinutes(10));
              assertThat(props.atom().maxTtl()).isEqualTo(Duration.ofHours(2));
            });
  }

  @Test
  void sweepersCreatedByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(AtomSweeper.class);
          assertThat(context).hasSingleBean(JournalSweeper.class);
          assertThat(context).hasSingleBean(MailboxSweeper.class);
        });
  }

  @Test
  void atomSweeperDisabledViaProperty() {
    contextRunner
        .withPropertyValues("substrate.atom.sweep.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(AtomSweeper.class));
  }

  @Test
  void journalSweeperDisabledViaProperty() {
    contextRunner
        .withPropertyValues("substrate.journal.sweep.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(JournalSweeper.class));
  }

  @Test
  void mailboxSweeperDisabledViaProperty() {
    contextRunner
        .withPropertyValues("substrate.mailbox.sweep.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(MailboxSweeper.class));
  }

  @Test
  void sweepIntervalConfigurationOverrideViaProperties() {
    contextRunner
        .withPropertyValues("substrate.atom.sweep.interval=PT5M")
        .run(
            context -> {
              SubstrateProperties props = context.getBean(SubstrateProperties.class);
              assertThat(props.atom().sweep().interval()).isEqualTo(Duration.ofMinutes(5));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class CodecFactoryConfiguration {

    @Bean
    CodecFactory codecFactory() {
      return new JacksonCodecFactory(JsonMapper.builder().build());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomJournalSpiConfiguration {

    @Bean
    JournalSpi customJournalSpi() {
      return new StubJournalSpi();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomMailboxSpiConfiguration {

    @Bean
    MailboxSpi customMailboxSpi() {
      return new StubMailboxSpi();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomNotifierConfiguration {

    @Bean
    NotifierSpi customNotifier() {
      return new StubNotifierSpi();
    }
  }

  static class StubJournalSpi implements JournalSpi {

    @Override
    public String append(String key, byte[] data, Duration ttl) {
      return "stub-id";
    }

    @Override
    public List<RawJournalEntry> readAfter(String key, String afterId) {
      return List.of();
    }

    @Override
    public List<RawJournalEntry> readLast(String key, int count) {
      return List.of();
    }

    @Override
    public void create(String key, Duration inactivityTtl) {
      // no-op stub for testing
    }

    @Override
    public void complete(String key, Duration retentionTtl) {
      // no-op stub for testing
    }

    @Override
    public void delete(String key) {
      // no-op stub for testing
    }

    @Override
    public boolean isComplete(String key) {
      return false;
    }

    @Override
    public int sweep(int maxToSweep) {
      return 0;
    }

    @Override
    public String journalKey(String name) {
      return "stub:" + name;
    }
  }

  static class StubMailboxSpi implements MailboxSpi {

    @Override
    public void create(String key, Duration ttl) {
      // no-op stub for testing
    }

    @Override
    public void deliver(String key, byte[] value) {
      // no-op stub for testing
    }

    @Override
    public Optional<byte[]> get(String key) {
      return Optional.empty();
    }

    @Override
    public void delete(String key) {
      // no-op stub for testing
    }

    @Override
    public int sweep(int maxToSweep) {
      return 0;
    }

    @Override
    public String mailboxKey(String name) {
      return "stub:" + name;
    }
  }

  static class StubNotifierSpi implements NotifierSpi {

    @Override
    public void notify(String key, String payload) {
      // no-op stub for testing
    }

    @Override
    public NotifierSubscription subscribe(NotificationHandler handler) {
      return () -> {
        // no-op stub for testing
      };
    }
  }
}
