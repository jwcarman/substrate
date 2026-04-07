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
package org.jwcarman.substrate.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryNotifierTest {

  private InMemoryNotifier notifier;

  @BeforeEach
  void setUp() {
    notifier = new InMemoryNotifier();
  }

  @Test
  void notifyCallsRegisteredHandler() {
    List<String> received = new ArrayList<>();
    notifier.subscribe((key, payload) -> received.add(key + ":" + payload));

    notifier.notify("substrate:test", "event-1");

    assertEquals(1, received.size());
    assertEquals("substrate:test:event-1", received.getFirst());
  }

  @Test
  void notifyCallsMultipleHandlers() {
    List<String> handler1 = new ArrayList<>();
    List<String> handler2 = new ArrayList<>();
    notifier.subscribe((key, payload) -> handler1.add(payload));
    notifier.subscribe((key, payload) -> handler2.add(payload));

    notifier.notify("substrate:test", "event-1");

    assertEquals(1, handler1.size());
    assertEquals(1, handler2.size());
  }

  @Test
  void notifyWithNoHandlersDoesNotThrow() {
    assertDoesNotThrow(() -> notifier.notify("substrate:test", "event-1"));
  }

  @Test
  void handlersReceiveCorrectKeyAndPayload() {
    List<String> keys = new ArrayList<>();
    List<String> payloads = new ArrayList<>();
    notifier.subscribe(
        (key, payload) -> {
          keys.add(key);
          payloads.add(payload);
        });

    notifier.notify("substrate:alpha", "payload-1");
    notifier.notify("substrate:beta", "payload-2");

    assertEquals(List.of("substrate:alpha", "substrate:beta"), keys);
    assertEquals(List.of("payload-1", "payload-2"), payloads);
  }

  @Test
  void multipleNotificationsDeliveredInOrder() {
    List<String> received = new ArrayList<>();
    notifier.subscribe((key, payload) -> received.add(payload));

    notifier.notify("substrate:test", "1");
    notifier.notify("substrate:test", "2");
    notifier.notify("substrate:test", "3");

    assertEquals(List.of("1", "2", "3"), received);
  }

  @Test
  void notifyIsSynchronous() {
    List<String> order = new ArrayList<>();
    notifier.subscribe((key, payload) -> order.add("handler-" + payload));

    notifier.notify("substrate:test", "1");
    order.add("after-notify");

    assertEquals(List.of("handler-1", "after-notify"), order);
  }
}
