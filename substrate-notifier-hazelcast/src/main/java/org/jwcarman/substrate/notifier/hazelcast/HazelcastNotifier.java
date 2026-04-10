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
package org.jwcarman.substrate.notifier.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.substrate.core.notifier.NotificationHandler;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.springframework.context.SmartLifecycle;

public class HazelcastNotifier implements NotifierSpi, SmartLifecycle {

  private final HazelcastInstance hazelcastInstance;
  private final String topicName;
  private final List<NotificationHandler> handlers = new CopyOnWriteArrayList<>();
  private final AtomicReference<UUID> registrationId = new AtomicReference<>();

  public HazelcastNotifier(HazelcastInstance hazelcastInstance, String topicName) {
    this.hazelcastInstance = hazelcastInstance;
    this.topicName = topicName;
  }

  @Override
  public void notify(String key, String payload) {
    ITopic<String> topic = hazelcastInstance.getTopic(topicName);
    topic.publish(key + "|" + payload);
  }

  @Override
  public NotifierSubscription subscribe(NotificationHandler handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void start() {
    ITopic<String> topic = hazelcastInstance.getTopic(topicName);
    UUID id =
        topic.addMessageListener(
            message -> {
              String raw = message.getMessageObject();
              int separatorIndex = raw.indexOf('|');
              String key = raw.substring(0, separatorIndex);
              String payload = raw.substring(separatorIndex + 1);
              for (NotificationHandler handler : handlers) {
                handler.onNotification(key, payload);
              }
            });
    registrationId.set(id);
  }

  @Override
  public void stop() {
    UUID id = registrationId.getAndSet(null);
    if (id != null) {
      ITopic<String> topic = hazelcastInstance.getTopic(topicName);
      topic.removeMessageListener(id);
    }
  }

  @Override
  public boolean isRunning() {
    return registrationId.get() != null;
  }
}
