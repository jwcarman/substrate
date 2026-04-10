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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.hazelcast.AbstractHazelcastIT;

class HazelcastNotifierSpiIT extends AbstractHazelcastIT {

  private HazelcastNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    notifier = new HazelcastNotifierSpi(hazelcast, "substrate-notify");
    notifier.start();
  }

  @AfterEach
  void tearDown() {
    if (notifier != null && notifier.isRunning()) {
      notifier.stop();
    }
  }

  @Test
  void publishAndSubscribeFullLifecycle() {
    List<String> received = new CopyOnWriteArrayList<>();
    notifier.subscribe((key, payload) -> received.add(key + "=" + payload));

    notifier.notify("test-key", "test-payload");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(received).containsExactly("test-key=test-payload"));
  }

  @Test
  void multipleNotificationsAreDelivered() {
    List<String> received = new CopyOnWriteArrayList<>();
    notifier.subscribe((key, payload) -> received.add(payload));

    notifier.notify("key-1", "payload-1");
    notifier.notify("key-2", "payload-2");
    notifier.notify("key-3", "payload-3");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(received).containsExactly("payload-1", "payload-2", "payload-3"));
  }

  @Test
  void multipleHandlersReceiveNotifications() {
    List<String> handler1 = new CopyOnWriteArrayList<>();
    List<String> handler2 = new CopyOnWriteArrayList<>();

    notifier.subscribe((key, payload) -> handler1.add(payload));
    notifier.subscribe((key, payload) -> handler2.add(payload));

    notifier.notify("key", "value");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(handler1).containsExactly("value");
              assertThat(handler2).containsExactly("value");
            });
  }

  @Test
  void payloadContainingPipeIsParsedCorrectly() {
    List<String> keys = new CopyOnWriteArrayList<>();
    List<String> payloads = new CopyOnWriteArrayList<>();
    notifier.subscribe(
        (key, payload) -> {
          keys.add(key);
          payloads.add(payload);
        });

    notifier.notify("my-key", "data|with|pipes");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(keys).containsExactly("my-key");
              assertThat(payloads).containsExactly("data|with|pipes");
            });
  }
}
