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
import java.util.function.Consumer;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.springframework.context.SmartLifecycle;

public class RedisNotifierSpi extends RedisPubSubAdapter<byte[], byte[]>
    implements NotifierSpi, SmartLifecycle {

  private final StatefulRedisPubSubConnection<byte[], byte[]> pubSubConnection;
  private final RedisPubSubCommands<byte[], byte[]> pubSubCommands;
  private final io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> publishCommands;
  private final byte[] channel;
  private final List<Consumer<byte[]>> handlers = new CopyOnWriteArrayList<>();

  private final AtomicBoolean running = new AtomicBoolean(false);

  public RedisNotifierSpi(
      StatefulRedisPubSubConnection<byte[], byte[]> pubSubConnection,
      io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> publishCommands,
      byte[] channel) {
    this.pubSubConnection = pubSubConnection;
    this.pubSubCommands = pubSubConnection.sync();
    this.publishCommands = publishCommands;
    this.channel = channel;
  }

  @Override
  public void notify(byte[] payload) {
    publishCommands.publish(channel, payload);
  }

  @Override
  public NotifierSubscription subscribe(Consumer<byte[]> handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void message(byte[] channel, byte[] message) {
    for (Consumer<byte[]> handler : handlers) {
      handler.accept(message);
    }
  }

  @Override
  public void start() {
    pubSubConnection.addListener(this);
    pubSubCommands.subscribe(channel);
    running.set(true);
  }

  @Override
  public void stop() {
    running.set(false);
    pubSubCommands.unsubscribe(channel);
    pubSubConnection.removeListener(this);
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }
}
