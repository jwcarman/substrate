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
package org.jwcarman.substrate.rabbitmq.notifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;

public class RabbitMqNotifierSpi implements NotifierSpi, SmartLifecycle {

  private final RabbitTemplate rabbitTemplate;
  private final ConnectionFactory connectionFactory;
  private final String exchangeName;
  private final List<Consumer<byte[]>> handlers = new CopyOnWriteArrayList<>();

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<SimpleMessageListenerContainer> listenerContainer =
      new AtomicReference<>();

  public RabbitMqNotifierSpi(
      RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, String exchangeName) {
    this.rabbitTemplate = rabbitTemplate;
    this.connectionFactory = connectionFactory;
    this.exchangeName = exchangeName;
  }

  @Override
  public void notify(byte[] payload) {
    rabbitTemplate.send(exchangeName, "", new Message(payload));
  }

  @Override
  public NotifierSubscription subscribe(Consumer<byte[]> handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void start() {
    RabbitAdmin admin = new RabbitAdmin(connectionFactory);

    FanoutExchange exchange = new FanoutExchange(exchangeName, true, false);
    admin.declareExchange(exchange);

    Queue queue = new Queue("", false, true, true);
    String queueName = admin.declareQueue(queue);

    admin.declareBinding(BindingBuilder.bind(queue).to(exchange));

    SimpleMessageListenerContainer container =
        new SimpleMessageListenerContainer(connectionFactory);
    container.setQueueNames(queueName);
    container.setMessageListener((MessageListener) this::handleMessage);
    container.start();
    listenerContainer.set(container);

    running.set(true);
  }

  @Override
  public void stop() {
    running.set(false);
    SimpleMessageListenerContainer container = listenerContainer.getAndSet(null);
    if (container != null) {
      container.stop();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void handleMessage(Message message) {
    byte[] body = message.getBody();
    for (Consumer<byte[]> handler : handlers) {
      handler.accept(body);
    }
  }
}
