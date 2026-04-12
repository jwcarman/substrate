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
package org.jwcarman.substrate.redis.notifier;

import static java.nio.charset.StandardCharsets.UTF_8;
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
class RedisNotifierSpiTest {

  private static final byte[] CHANNEL = "substrate:notifications".getBytes(UTF_8);

  @Mock private StatefulRedisPubSubConnection<byte[], byte[]> pubSubConnection;
  @Mock private RedisPubSubCommands<byte[], byte[]> pubSubCommands;
  @Mock private RedisCommands<byte[], byte[]> publishCommands;

  private RedisNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    when(pubSubConnection.sync()).thenReturn(pubSubCommands);
    notifier = new RedisNotifierSpi(pubSubConnection, publishCommands, CHANNEL);
  }

  @Test
  void notifyPublishesPayloadToChannel() {
    byte[] payload = "my-payload".getBytes(UTF_8);

    notifier.notify(payload);

    verify(publishCommands).publish(CHANNEL, payload);
  }

  @Test
  void subscribeDispatchesIncomingMessagesToHandler() {
    List<byte[]> received = new ArrayList<>();
    notifier.subscribe(received::add);

    notifier.message(CHANNEL, "data-42".getBytes(UTF_8));

    assertThat(received).hasSize(1);
    assertThat(new String(received.get(0), UTF_8)).isEqualTo("data-42");
  }

  @Test
  void startIssuesSubscribeOnDedicatedConnection() {
    notifier.start();

    verify(pubSubConnection).addListener(notifier);
    verify(pubSubCommands).subscribe(CHANNEL);
    assertThat(notifier.isRunning()).isTrue();
  }

  @Test
  void stopUnsubscribesAndRemovesListener() {
    notifier.start();
    notifier.stop();

    verify(pubSubCommands).unsubscribe(CHANNEL);
    verify(pubSubConnection).removeListener(notifier);
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void multipleHandlersAllReceiveNotifications() {
    List<byte[]> handler1 = new ArrayList<>();
    List<byte[]> handler2 = new ArrayList<>();

    notifier.subscribe(handler1::add);
    notifier.subscribe(handler2::add);

    byte[] payload = "value".getBytes(UTF_8);
    notifier.message(CHANNEL, payload);

    assertThat(handler1).hasSize(1);
    assertThat(new String(handler1.get(0), UTF_8)).isEqualTo("value");
    assertThat(handler2).hasSize(1);
    assertThat(new String(handler2.get(0), UTF_8)).isEqualTo("value");
  }
}
