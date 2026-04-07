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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.memory.InMemoryJournal;
import org.jwcarman.substrate.memory.InMemoryMailbox;
import org.jwcarman.substrate.memory.InMemoryNotifier;
import org.jwcarman.substrate.spi.Journal;
import org.jwcarman.substrate.spi.JournalEntry;
import org.jwcarman.substrate.spi.Mailbox;
import org.jwcarman.substrate.spi.NotificationHandler;
import org.jwcarman.substrate.spi.Notifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
  void createsInMemoryJournalWhenNoOtherBeanExists() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(Journal.class);
          assertThat(context.getBean(Journal.class)).isInstanceOf(InMemoryJournal.class);
        });
  }

  @Test
  void createsInMemoryMailboxWhenNoOtherBeanExists() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(Mailbox.class);
          assertThat(context.getBean(Mailbox.class)).isInstanceOf(InMemoryMailbox.class);
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
  void doesNotCreateInMemoryJournalWhenExternalBeanExists() {
    contextRunner
        .withUserConfiguration(CustomJournalConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Journal.class);
              assertThat(context.getBean(Journal.class)).isInstanceOf(StubJournal.class);
              assertThat(context).doesNotHaveBean(InMemoryJournal.class);
            });
  }

  @Test
  void doesNotCreateInMemoryMailboxWhenExternalBeanExists() {
    contextRunner
        .withUserConfiguration(CustomMailboxConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Mailbox.class);
              assertThat(context.getBean(Mailbox.class)).isInstanceOf(StubMailbox.class);
              assertThat(context).doesNotHaveBean(InMemoryMailbox.class);
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
          assertThat(context).hasSingleBean(InMemoryJournal.class);
          assertThat(context).hasSingleBean(InMemoryMailbox.class);
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
            CustomJournalConfiguration.class,
            CustomMailboxConfiguration.class,
            CustomNotifierConfiguration.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(InMemoryJournal.class);
              assertThat(context).doesNotHaveBean(InMemoryMailbox.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
    assertThat(output)
        .doesNotContain("No Journal implementation found; using in-memory fallback")
        .doesNotContain("No Mailbox implementation found; using in-memory fallback")
        .doesNotContain("No Notifier implementation found; using in-memory fallback");
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomJournalConfiguration {

    @Bean
    Journal customJournal() {
      return new StubJournal();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomMailboxConfiguration {

    @Bean
    Mailbox customMailbox() {
      return new StubMailbox();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomNotifierConfiguration {

    @Bean
    Notifier customNotifier() {
      return new StubNotifier();
    }
  }

  static class StubJournal implements Journal {

    @Override
    public String append(String key, String data) {
      return "stub-id";
    }

    @Override
    public String append(String key, String data, Duration ttl) {
      return "stub-id";
    }

    @Override
    public Stream<JournalEntry> readAfter(String key, String afterId) {
      return Stream.empty();
    }

    @Override
    public Stream<JournalEntry> readLast(String key, int count) {
      return Stream.empty();
    }

    @Override
    public void complete(String key) {}

    @Override
    public void delete(String key) {}

    @Override
    public String journalKey(String name) {
      return "stub:" + name;
    }
  }

  static class StubMailbox implements Mailbox {

    @Override
    public void deliver(String key, String value) {}

    @Override
    public CompletableFuture<String> await(String key, Duration timeout) {
      return CompletableFuture.completedFuture("stub");
    }

    @Override
    public void delete(String key) {}

    @Override
    public String mailboxKey(String name) {
      return "stub:" + name;
    }
  }

  static class StubNotifier implements Notifier {

    @Override
    public void notify(String key, String payload) {}

    @Override
    public void subscribe(NotificationHandler handler) {}
  }
}
