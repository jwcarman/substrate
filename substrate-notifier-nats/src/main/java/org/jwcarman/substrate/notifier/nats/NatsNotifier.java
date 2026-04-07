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
package org.jwcarman.substrate.notifier.nats;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.substrate.spi.NotificationHandler;
import org.jwcarman.substrate.spi.Notifier;
import org.jwcarman.substrate.spi.NotifierSubscription;
import org.springframework.context.SmartLifecycle;

public class NatsNotifier implements Notifier, SmartLifecycle {

  private final Connection connection;
  private final String subjectPrefix;
  private final List<NotificationHandler> handlers = new CopyOnWriteArrayList<>();

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<Dispatcher> dispatcher = new AtomicReference<>();

  public NatsNotifier(Connection connection, String subjectPrefix) {
    this.connection = connection;
    this.subjectPrefix = toSubjectPrefix(subjectPrefix);
  }

  @Override
  public void notify(String key, String payload) {
    String subject = toSubject(key);
    connection.publish(subject, payload.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public NotifierSubscription subscribe(NotificationHandler handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void start() {
    Dispatcher d = connection.createDispatcher(this::handleMessage);
    d.subscribe(subjectPrefix + ">");
    dispatcher.set(d);
    running.set(true);
  }

  @Override
  public void stop() {
    running.set(false);
    Dispatcher d = dispatcher.getAndSet(null);
    if (d != null) {
      connection.closeDispatcher(d);
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void handleMessage(Message message) {
    String key = toKey(message.getSubject());
    String payload = new String(message.getData(), StandardCharsets.UTF_8);
    for (NotificationHandler handler : handlers) {
      handler.onNotification(key, payload);
    }
  }

  private String toSubject(String key) {
    return subjectPrefix + key.replace(':', '.');
  }

  private String toKey(String subject) {
    return subject.substring(subjectPrefix.length()).replace('.', ':');
  }

  // Ensures the subject prefix uses dots (NATS convention) even if configured with colons
  private static String toSubjectPrefix(String prefix) {
    return prefix.replace(':', '.');
  }
}
