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

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.springframework.context.SmartLifecycle;

public class HazelcastNotifierSpi implements NotifierSpi, SmartLifecycle {

  private final HazelcastInstance hazelcastInstance;
  private final String topicName;
  private final List<Consumer<byte[]>> handlers = new CopyOnWriteArrayList<>();
  private final AtomicReference<UUID> registrationId = new AtomicReference<>();

  public HazelcastNotifierSpi(HazelcastInstance hazelcastInstance, String topicName) {
    this.hazelcastInstance = hazelcastInstance;
    this.topicName = topicName;
  }

  @Override
  public void notify(byte[] payload) {
    ITopic<byte[]> topic = hazelcastInstance.getTopic(topicName);
    topic.publish(payload);
  }

  @Override
  public NotifierSubscription subscribe(Consumer<byte[]> handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void start() {
    ITopic<byte[]> topic = hazelcastInstance.getTopic(topicName);
    UUID id =
        topic.addMessageListener(
            message -> {
              byte[] data = message.getMessageObject();
              for (Consumer<byte[]> handler : handlers) {
                handler.accept(data);
              }
            });
    registrationId.set(id);
  }

  @Override
  public void stop() {
    UUID id = registrationId.getAndSet(null);
    if (id != null) {
      ITopic<byte[]> topic = hazelcastInstance.getTopic(topicName);
      topic.removeMessageListener(id);
    }
  }

  @Override
  public boolean isRunning() {
    return registrationId.get() != null;
  }
}
