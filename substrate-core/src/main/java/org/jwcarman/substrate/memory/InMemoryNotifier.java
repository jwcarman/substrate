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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.substrate.spi.NotificationHandler;
import org.jwcarman.substrate.spi.Notifier;
import org.jwcarman.substrate.spi.NotifierSubscription;

public class InMemoryNotifier implements Notifier {

  private final List<NotificationHandler> handlers = new CopyOnWriteArrayList<>();

  @Override
  public void notify(String key, String payload) {
    for (NotificationHandler handler : handlers) {
      handler.onNotification(key, payload);
    }
  }

  @Override
  public NotifierSubscription subscribe(NotificationHandler handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }
}
