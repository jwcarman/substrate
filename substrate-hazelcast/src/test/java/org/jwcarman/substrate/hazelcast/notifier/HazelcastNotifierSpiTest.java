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
package org.jwcarman.substrate.hazelcast.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HazelcastNotifierSpiTest {

  @Mock private HazelcastInstance hazelcastInstance;
  @Mock private ITopic<String> topic;

  private HazelcastNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    notifier = new HazelcastNotifierSpi(hazelcastInstance, "substrate-notify");
  }

  private void stubGetTopic() {
    when(hazelcastInstance.<String>getTopic("substrate-notify")).thenReturn(topic);
  }

  @Test
  void notifyPublishesKeyAndPayloadToTopic() {
    stubGetTopic();

    notifier.notify("my-key", "my-payload");

    verify(topic).publish("my-key|my-payload");
  }

  @Test
  void startAddsMessageListenerAndBecomesRunning() {
    stubGetTopic();
    when(topic.addMessageListener(any())).thenReturn(UUID.randomUUID());

    notifier.start();

    verify(topic).addMessageListener(any());
    assertThat(notifier.isRunning()).isTrue();
  }

  @Test
  void stopRemovesMessageListenerAndBecomesNotRunning() {
    stubGetTopic();
    UUID id = UUID.randomUUID();
    when(topic.addMessageListener(any())).thenReturn(id);

    notifier.start();
    notifier.stop();

    verify(topic).removeMessageListener(id);
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void stopWhenNotRunningIsNoOp() {
    notifier.stop();
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void multipleHandlersAreRegistered() {
    List<String> handler1 = new ArrayList<>();
    List<String> handler2 = new ArrayList<>();

    notifier.subscribe((key, payload) -> handler1.add(payload));
    notifier.subscribe((key, payload) -> handler2.add(payload));

    assertThat(handler1).isEmpty();
    assertThat(handler2).isEmpty();
  }
}
