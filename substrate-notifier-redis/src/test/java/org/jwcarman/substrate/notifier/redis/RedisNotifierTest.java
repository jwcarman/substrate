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
package org.jwcarman.substrate.notifier.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisNotifierTest {

  @Mock private StatefulRedisPubSubConnection<String, String> pubSubConnection;
  @Mock private RedisPubSubCommands<String, String> pubSubCommands;
  @Mock private RedisCommands<String, String> publishCommands;

  private RedisNotifier notifier;

  @BeforeEach
  void setUp() {
    when(pubSubConnection.sync()).thenReturn(pubSubCommands);
    notifier = new RedisNotifier(pubSubConnection, publishCommands, "substrate:notify:");
  }

  @Test
  void notifyPublishesPayloadToChannel() {
    notifier.notify("my-key", "my-payload");

    verify(publishCommands).publish("substrate:notify:my-key", "my-payload");
  }

  @Test
  void subscribeDispatchesIncomingMessagesToHandler() {
    List<String> received = new ArrayList<>();
    notifier.subscribe((key, payload) -> received.add(key + "=" + payload));

    notifier.message("substrate:notify:*", "substrate:notify:test-key", "data-42");

    assertThat(received).containsExactly("test-key=data-42");
  }

  @Test
  void keyIsExtractedByStrippingChannelPrefix() {
    List<String> keys = new ArrayList<>();
    notifier.subscribe((key, payload) -> keys.add(key));

    notifier.message("substrate:notify:*", "substrate:notify:substrate:mailbox:abc-123", "value");

    assertThat(keys).containsExactly("substrate:mailbox:abc-123");
  }

  @Test
  void startIssuesPsubscribeOnDedicatedConnection() {
    notifier.start();

    verify(pubSubConnection).addListener(notifier);
    verify(pubSubCommands).psubscribe("substrate:notify:*");
    assertThat(notifier.isRunning()).isTrue();
  }

  @Test
  void stopUnsubscribesAndRemovesListener() {
    notifier.start();
    notifier.stop();

    verify(pubSubCommands).punsubscribe("substrate:notify:*");
    verify(pubSubConnection).removeListener(notifier);
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void nonPatternMessageIsIgnored() {
    List<String> received = new ArrayList<>();
    notifier.subscribe((key, payload) -> received.add(payload));
    notifier.message("substrate:notify:test-key", "data");
    assertThat(received).isEmpty();
  }

  @Test
  void multipleHandlersAllReceiveNotifications() {
    List<String> handler1 = new ArrayList<>();
    List<String> handler2 = new ArrayList<>();

    notifier.subscribe((key, payload) -> handler1.add(payload));
    notifier.subscribe((key, payload) -> handler2.add(payload));

    notifier.message("substrate:notify:*", "substrate:notify:key", "value");

    assertThat(handler1).containsExactly("value");
    assertThat(handler2).containsExactly("value");
  }
}
