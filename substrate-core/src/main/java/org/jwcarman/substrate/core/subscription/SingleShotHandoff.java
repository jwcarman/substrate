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
package org.jwcarman.substrate.core.subscription;

import java.util.List;
import org.jwcarman.substrate.NextResult;

/**
 * Single-push sealed handoff strategy. Accepts exactly one value via {@link #push push}, then
 * auto-transitions to {@link org.jwcarman.substrate.NextResult.Completed Completed} after that
 * value is consumed. Used by {@link org.jwcarman.substrate.mailbox.Mailbox Mailbox} subscriptions.
 *
 * @param <T> the type of values transferred through the handoff
 */
public class SingleShotHandoff<T> extends AbstractSingleSlotHandoff<T> {

  @Override
  public void push(T item) {
    lock.lock();
    try {
      if (sealed) return;
      slot = new NextResult.Value<>(item);
      sealed = true;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void pushAll(List<T> items) {
    if (items.isEmpty()) return;
    push(items.get(0));
  }

  @Override
  protected NextResult<T> consumeSlot() {
    return new NextResult.Completed<>();
  }
}
