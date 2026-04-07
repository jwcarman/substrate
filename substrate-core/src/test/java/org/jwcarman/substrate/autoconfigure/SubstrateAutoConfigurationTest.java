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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.substrate.core.JournalFactory;
import org.jwcarman.substrate.core.MailboxFactory;
import org.jwcarman.substrate.memory.InMemoryJournalSpi;
import org.jwcarman.substrate.memory.InMemoryMailboxSpi;
import org.jwcarman.substrate.memory.InMemoryNotifier;
import org.jwcarman.substrate.spi.JournalSpi;
import org.jwcarman.substrate.spi.MailboxSpi;
import org.jwcarman.substrate.spi.NotificationHandler;
import org.jwcarman.substrate.spi.Notifier;
import org.jwcarman.substrate.spi.NotifierSubscription;
import org.jwcarman.substrate.spi.RawJournalEntry;
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
          assertThat(context).hasSingleBean(Notifier.class);
          assertThat(context.getBean(Notifier.class)).isInstanceOf(InMemoryNotifier.class);
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
              assertThat(context).hasSingleBean(Notifier.class);
              assertThat(context.getBean(Notifier.class)).isInstanceOf(StubNotifier.class);
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
        .contains("No Notifier implementation found; using in-memory fallback");
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
        .doesNotContain("No Notifier implementation found; using in-memory fallback");
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
    Notifier customNotifier() {
      return new StubNotifier();
    }
  }

  static class StubJournalSpi implements JournalSpi {

    @Override
    public String append(String key, byte[] data) {
      return "stub-id";
    }

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
    public void complete(String key) {
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
    public String journalKey(String name) {
      return "stub:" + name;
    }
  }

  static class StubMailboxSpi implements MailboxSpi {

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
    public String mailboxKey(String name) {
      return "stub:" + name;
    }
  }

  static class StubNotifier implements Notifier {

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
