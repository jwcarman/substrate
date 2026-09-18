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

import java.util.function.Consumer;

/**
 * Routes {@link Notification}s from the writer of a primitive to whoever is subscribed to it,
 * across nodes when the backing transport spans them.
 *
 * <p>A notifier is a wake-up channel, not a delivery guarantee: a dropped notification costs a
 * subscriber latency, not correctness, because subscribers re-read the primitive to learn what
 * actually changed. Primitives call the {@code notify*} methods after a successful write; the
 * {@code subscribeTo*} methods are how subscriptions listen.
 */
public interface Notifier {

  /**
   * Announces that the atom at this key was written.
   *
   * @param key the backend storage key
   */
  void notifyAtomChanged(String key);

  /**
   * Announces that the atom at this key was deleted.
   *
   * @param key the backend storage key
   */
  void notifyAtomDeleted(String key);

  /**
   * Announces that the journal at this key was appended to.
   *
   * @param key the backend storage key
   */
  void notifyJournalChanged(String key);

  /**
   * Announces that the journal at this key was completed.
   *
   * @param key the backend storage key
   */
  void notifyJournalCompleted(String key);

  /**
   * Announces that the journal at this key was deleted.
   *
   * @param key the backend storage key
   */
  void notifyJournalDeleted(String key);

  /**
   * Announces that the mailbox at this key was written.
   *
   * @param key the backend storage key
   */
  void notifyMailboxChanged(String key);

  /**
   * Announces that the mailbox at this key was deleted.
   *
   * @param key the backend storage key
   */
  void notifyMailboxDeleted(String key);

  /**
   * Subscribes to notifications about the atom at the given key.
   *
   * @param key the backend storage key
   * @param handler invoked for each notification about this key
   * @return a handle that stops delivery when closed
   */
  NotifierSubscription subscribeToAtom(String key, Consumer<Notification> handler);

  /**
   * Subscribes to notifications about the journal at the given key.
   *
   * @param key the backend storage key
   * @param handler invoked for each notification about this key
   * @return a handle that stops delivery when closed
   */
  NotifierSubscription subscribeToJournal(String key, Consumer<Notification> handler);

  /**
   * Subscribes to notifications about the mailbox at the given key.
   *
   * @param key the backend storage key
   * @param handler invoked for each notification about this key
   * @return a handle that stops delivery when closed
   */
  NotifierSubscription subscribeToMailbox(String key, Consumer<Notification> handler);
}
