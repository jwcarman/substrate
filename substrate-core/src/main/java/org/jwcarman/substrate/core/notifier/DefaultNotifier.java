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
package org.jwcarman.substrate.core.notifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;

public class DefaultNotifier implements Notifier {

  private static final Log log = LogFactory.getLog(DefaultNotifier.class);

  private final NotifierSpi spi;
  private final Codec<RawNotification> codec;
  private final Map<PrimitiveType, Map<String, CopyOnWriteArrayList<Consumer<Notification>>>>
      index = new ConcurrentHashMap<>();

  public DefaultNotifier(NotifierSpi spi, CodecFactory codecFactory) {
    this.spi = spi;
    this.codec = codecFactory.create(RawNotification.class);
    spi.subscribe(this::onWire);
  }

  @Override
  public void notifyAtomChanged(String key) {
    publish(PrimitiveType.ATOM, key, EventType.CHANGED);
  }

  @Override
  public void notifyAtomDeleted(String key) {
    publish(PrimitiveType.ATOM, key, EventType.DELETED);
  }

  @Override
  public void notifyJournalChanged(String key) {
    publish(PrimitiveType.JOURNAL, key, EventType.CHANGED);
  }

  @Override
  public void notifyJournalCompleted(String key) {
    publish(PrimitiveType.JOURNAL, key, EventType.COMPLETED);
  }

  @Override
  public void notifyJournalDeleted(String key) {
    publish(PrimitiveType.JOURNAL, key, EventType.DELETED);
  }

  @Override
  public void notifyMailboxChanged(String key) {
    publish(PrimitiveType.MAILBOX, key, EventType.CHANGED);
  }

  @Override
  public void notifyMailboxDeleted(String key) {
    publish(PrimitiveType.MAILBOX, key, EventType.DELETED);
  }

  @Override
  public NotifierSubscription subscribeToAtom(String key, Consumer<Notification> handler) {
    return register(PrimitiveType.ATOM, key, handler);
  }

  @Override
  public NotifierSubscription subscribeToJournal(String key, Consumer<Notification> handler) {
    return register(PrimitiveType.JOURNAL, key, handler);
  }

  @Override
  public NotifierSubscription subscribeToMailbox(String key, Consumer<Notification> handler) {
    return register(PrimitiveType.MAILBOX, key, handler);
  }

  private void publish(PrimitiveType type, String key, EventType event) {
    spi.notify(codec.encode(new RawNotification(key, type, event)));
  }

  private NotifierSubscription register(
      PrimitiveType type, String key, Consumer<Notification> handler) {
    var handlers =
        index
            .computeIfAbsent(type, t -> new ConcurrentHashMap<>())
            .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  private void onWire(byte[] payload) {
    RawNotification raw;
    try {
      raw = codec.decode(payload);
    } catch (RuntimeException e) {
      log.warn("Dropping malformed notification", e);
      return;
    }
    var byKey = index.get(raw.primitiveType());
    if (byKey == null) {
      return;
    }
    var handlers = byKey.get(raw.key());
    if (handlers == null) {
      return;
    }
    var typed = toTyped(raw);
    for (var h : handlers) {
      try {
        h.accept(typed);
      } catch (RuntimeException e) {
        log.warn("Notification handler threw", e);
      }
    }
  }

  private static Notification toTyped(RawNotification raw) {
    return switch (raw.eventType()) {
      case CHANGED -> new Notification.Changed(raw.key());
      case COMPLETED -> new Notification.Completed(raw.key());
      case DELETED -> new Notification.Deleted(raw.key());
    };
  }
}
