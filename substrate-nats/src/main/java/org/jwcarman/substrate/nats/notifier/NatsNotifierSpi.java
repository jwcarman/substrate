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
package org.jwcarman.substrate.nats.notifier;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.springframework.context.SmartLifecycle;

public class NatsNotifierSpi implements NotifierSpi, SmartLifecycle {

  private final Connection connection;
  private final String subject;
  private final List<Consumer<byte[]>> handlers = new CopyOnWriteArrayList<>();

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<Dispatcher> dispatcher = new AtomicReference<>();

  public NatsNotifierSpi(Connection connection, String subject) {
    this.connection = connection;
    this.subject = subject;
  }

  @Override
  public void notify(byte[] payload) {
    connection.publish(subject, payload);
  }

  @Override
  public NotifierSubscription subscribe(Consumer<byte[]> handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void start() {
    Dispatcher d =
        connection.createDispatcher(
            message -> {
              byte[] data = message.getData();
              for (Consumer<byte[]> handler : handlers) {
                handler.accept(data);
              }
            });
    d.subscribe(subject);
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
}
