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

/**
 * Something that happened to a primitive at a given key, delivered to subscribers so they know to
 * look again. A notification carries no payload beyond the key — it is a wake-up, not a value, and
 * the subscriber reads the primitive to find out what changed.
 */
public sealed interface Notification
    permits Notification.Changed, Notification.Completed, Notification.Deleted {

  /**
   * Returns the backend storage key the notification is about.
   *
   * @return the backend storage key
   */
  String key();

  /**
   * The value at this key was written: an atom set, a journal appended to, a mailbox filled.
   *
   * @param key the backend storage key
   */
  record Changed(String key) implements Notification {}

  /**
   * The journal at this key was completed and will accept no further appends.
   *
   * @param key the backend storage key
   */
  record Completed(String key) implements Notification {}

  /**
   * The primitive at this key was deleted.
   *
   * @param key the backend storage key
   */
  record Deleted(String key) implements Notification {}
}
