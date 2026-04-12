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
package org.jwcarman.substrate.core.memory.notifier;

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
    List<byte[]> received = new ArrayList<>();
    notifier.subscribe(received::add);

    byte[] payload = new byte[] {1, 2, 3};
    notifier.notify(payload);

    assertEquals(1, received.size());
    assertArrayEquals(payload, received.getFirst());
  }

  @Test
  void notifyCallsMultipleHandlers() {
    List<byte[]> handler1 = new ArrayList<>();
    List<byte[]> handler2 = new ArrayList<>();
    notifier.subscribe(handler1::add);
    notifier.subscribe(handler2::add);

    notifier.notify(new byte[] {1});

    assertEquals(1, handler1.size());
    assertEquals(1, handler2.size());
  }

  @Test
  void notifyWithNoHandlersDoesNotThrow() {
    assertDoesNotThrow(() -> notifier.notify(new byte[] {1, 2, 3}));
  }

  @Test
  void handlersReceiveCorrectPayload() {
    List<byte[]> payloads = new ArrayList<>();
    notifier.subscribe(payloads::add);

    byte[] first = new byte[] {1};
    byte[] second = new byte[] {2};
    notifier.notify(first);
    notifier.notify(second);

    assertEquals(2, payloads.size());
    assertArrayEquals(first, payloads.get(0));
    assertArrayEquals(second, payloads.get(1));
  }

  @Test
  void multipleNotificationsDeliveredInOrder() {
    List<byte[]> received = new ArrayList<>();
    notifier.subscribe(received::add);

    notifier.notify(new byte[] {1});
    notifier.notify(new byte[] {2});
    notifier.notify(new byte[] {3});

    assertEquals(3, received.size());
    assertArrayEquals(new byte[] {1}, received.get(0));
    assertArrayEquals(new byte[] {2}, received.get(1));
    assertArrayEquals(new byte[] {3}, received.get(2));
  }

  @Test
  void notifyIsSynchronous() {
    List<String> order = new ArrayList<>();
    notifier.subscribe(payload -> order.add("handler"));

    notifier.notify(new byte[] {1});
    order.add("after-notify");

    assertEquals(List.of("handler", "after-notify"), order);
  }

  @Test
  void cancelledSubscriptionStopsReceiving() {
    List<byte[]> received = new ArrayList<>();
    var sub = notifier.subscribe(received::add);

    notifier.notify(new byte[] {1});
    assertEquals(1, received.size());

    sub.cancel();
    notifier.notify(new byte[] {2});
    assertEquals(1, received.size());
  }
}
