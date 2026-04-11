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
 * Single-slot latest-wins handoff strategy. The producer never blocks — each {@link #push push}
 * overwrites the previous unconsumed value. Used by {@link org.jwcarman.substrate.atom.Atom Atom}
 * subscriptions, where only the most recent state matters.
 *
 * @param <T> the type of values transferred through the handoff
 */
public class CoalescingHandoff<T> extends AbstractSingleSlotHandoff<T> {

  @Override
  public void push(T item) {
    lock.lock();
    try {
      if (sealed) return;
      slot = new NextResult.Value<>(item);
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void pushAll(List<T> items) {
    if (items.isEmpty()) return;
    push(items.get(items.size() - 1));
  }

  @Override
  protected NextResult<T> consumeSlot() {
    return null;
  }
}
