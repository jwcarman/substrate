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

public interface Notifier {

  void notifyAtomChanged(String key);

  void notifyAtomDeleted(String key);

  void notifyJournalChanged(String key);

  void notifyJournalCompleted(String key);

  void notifyJournalDeleted(String key);

  void notifyMailboxChanged(String key);

  void notifyMailboxDeleted(String key);

  NotifierSubscription subscribeToAtom(String key, Consumer<Notification> handler);

  NotifierSubscription subscribeToJournal(String key, Consumer<Notification> handler);

  NotifierSubscription subscribeToMailbox(String key, Consumer<Notification> handler);
}
