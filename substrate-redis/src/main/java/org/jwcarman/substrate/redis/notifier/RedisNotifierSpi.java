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

import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.substrate.core.notifier.NotificationHandler;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.springframework.context.SmartLifecycle;

public class RedisNotifierSpi extends RedisPubSubAdapter<String, String>
    implements NotifierSpi, SmartLifecycle {

  private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
  private final RedisPubSubCommands<String, String> pubSubCommands;
  private final io.lettuce.core.api.sync.RedisCommands<String, String> publishCommands;
  private final String channelPrefix;
  private final String subscribePattern;
  private final List<NotificationHandler> handlers = new CopyOnWriteArrayList<>();

  private final AtomicBoolean running = new AtomicBoolean(false);

  public RedisNotifierSpi(
      StatefulRedisPubSubConnection<String, String> pubSubConnection,
      io.lettuce.core.api.sync.RedisCommands<String, String> publishCommands,
      String channelPrefix) {
    this.pubSubConnection = pubSubConnection;
    this.pubSubCommands = pubSubConnection.sync();
    this.publishCommands = publishCommands;
    this.channelPrefix = channelPrefix;
    this.subscribePattern = channelPrefix + "*";
  }

  @Override
  public void notify(String key, String payload) {
    publishCommands.publish(channelPrefix + key, payload);
  }

  @Override
  public NotifierSubscription subscribe(NotificationHandler handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void message(String channel, String message) {
    // Not used — we only use pattern subscriptions
  }

  @Override
  public void message(String pattern, String channel, String message) {
    String key = channel.substring(channelPrefix.length());
    for (NotificationHandler handler : handlers) {
      handler.onNotification(key, message);
    }
  }

  @Override
  public void start() {
    pubSubConnection.addListener(this);
    pubSubCommands.psubscribe(subscribePattern);
    running.set(true);
  }

  @Override
  public void stop() {
    running.set(false);
    pubSubCommands.punsubscribe(subscribePattern);
    pubSubConnection.removeListener(this);
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }
}
