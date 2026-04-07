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
package org.jwcarman.substrate.core;

import java.time.Duration;
import java.util.stream.Stream;

public interface Journal<T> {
  String append(T data);

  String append(T data, Duration ttl);

  Stream<JournalEntry<T>> readAfter(String afterId);

  Stream<JournalEntry<T>> readLast(int count);

  void complete();

  void delete();

  String key();

  Subscription subscribe(JournalSubscriber<T> subscriber);

  Subscription subscribe(String afterId, JournalSubscriber<T> subscriber);
}
