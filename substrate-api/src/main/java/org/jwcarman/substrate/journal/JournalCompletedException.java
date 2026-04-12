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
package org.jwcarman.substrate.journal;

/**
 * Thrown when {@link Journal#append(Object, java.time.Duration) append} is called on a journal that
 * has already been {@linkplain Journal#complete(java.time.Duration) completed}.
 *
 * <p>Once a journal is completed no further entries may be appended, though existing entries remain
 * readable until the retention TTL elapses.
 *
 * @see Journal#complete(java.time.Duration)
 */
public class JournalCompletedException extends RuntimeException {

  /**
   * Creates an exception indicating the journal with the given backend key has already been
   * completed.
   *
   * @param key the backend key of the completed journal
   */
  public JournalCompletedException(String key) {
    super("Journal has been completed: " + key);
  }
}
