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

/**
 * Single-slot latest-wins handoff. The {@code value} slot is consumed on read and overwritten on
 * every {@link #deliver(Object)}; the terminal marker is sticky once set. Used by Atom and Mailbox
 * subscriptions, whose feeders guarantee that only the most recent (or only) value matters.
 *
 * @param <T> the type of values transferred through the handoff
 */
public final class SingleSlotHandoff<T> extends AbstractHandoff<T> {

  private T value;

  @Override
  public void deliver(T item) {
    lock.lock();
    try {
      if (terminal != null) return;
      value = item;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected T takeValue() {
    T v = value;
    value = null;
    return v;
  }
}
