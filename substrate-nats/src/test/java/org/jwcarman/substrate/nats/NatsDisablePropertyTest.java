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
package org.jwcarman.substrate.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.KeyValueManagement;
import io.nats.client.MessageHandler;
import io.nats.client.api.KeyValueStatus;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.nats.journal.NatsJournalAutoConfiguration;
import org.jwcarman.substrate.nats.journal.NatsJournalSpi;
import org.jwcarman.substrate.nats.mailbox.NatsMailboxAutoConfiguration;
import org.jwcarman.substrate.nats.mailbox.NatsMailboxSpi;
import org.jwcarman.substrate.nats.notifier.NatsNotifierAutoConfiguration;
import org.jwcarman.substrate.nats.notifier.NatsNotifierSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NatsDisablePropertyTest {

  @Test
  void journalDisabledWhenPropertyIsFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(NatsAutoConfiguration.class, NatsJournalAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .withPropertyValues("substrate.nats.journal.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(NatsJournalSpi.class));
  }

  @Test
  void mailboxDisabledWhenPropertyIsFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(NatsAutoConfiguration.class, NatsMailboxAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .withPropertyValues("substrate.nats.mailbox.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(NatsMailboxSpi.class));
  }

  @Test
  void notifierDisabledWhenPropertyIsFalse() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(NatsAutoConfiguration.class, NatsNotifierAutoConfiguration.class))
        .withUserConfiguration(MockNatsConfiguration.class)
        .withPropertyValues("substrate.nats.notifier.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(NatsNotifierSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockNatsConfiguration {

    @Bean
    Connection connection() throws Exception {
      Connection conn = mock(Connection.class);
      JetStream js = mock(JetStream.class);
      JetStreamManagement jsm = mock(JetStreamManagement.class);
      when(conn.jetStream()).thenReturn(js);
      when(conn.jetStreamManagement()).thenReturn(jsm);
      when(jsm.addStream(any(StreamConfiguration.class))).thenReturn(null);
      KeyValueManagement kvm = mock(KeyValueManagement.class);
      when(conn.keyValueManagement()).thenReturn(kvm);
      when(kvm.getStatus("substrate-mailbox")).thenReturn(mock(KeyValueStatus.class));
      Dispatcher dispatcher = mock(Dispatcher.class);
      when(conn.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
      when(dispatcher.subscribe(any(String.class))).thenReturn(dispatcher);
      return conn;
    }
  }
}
