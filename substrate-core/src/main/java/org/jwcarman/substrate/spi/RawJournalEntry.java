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
package org.jwcarman.substrate.spi;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public record RawJournalEntry(String id, String key, byte[] data, Instant timestamp) {

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o
        instanceof
        RawJournalEntry(String thatId, String thatKey, byte[] thatData, Instant thatTimestamp))) {
      return false;
    }
    return Objects.equals(id, thatId)
        && Objects.equals(key, thatKey)
        && Arrays.equals(data, thatData)
        && Objects.equals(timestamp, thatTimestamp);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(id, key, timestamp);
    result = 31 * result + Arrays.hashCode(data);
    return result;
  }

  @Override
  public String toString() {
    return "RawJournalEntry["
        + "id="
        + id
        + ", key="
        + key
        + ", data="
        + Arrays.toString(data)
        + ", timestamp="
        + timestamp
        + ']';
  }
}
