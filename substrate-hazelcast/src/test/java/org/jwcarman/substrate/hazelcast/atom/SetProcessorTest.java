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
package org.jwcarman.substrate.hazelcast.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetProcessorTest {

  private static final byte[] VALUE = "v".getBytes(StandardCharsets.UTF_8);

  @Test
  void returnsFalseWhenEntryHasNoValue() {
    SetProcessor processor = new SetProcessor(VALUE, "tok", 1000L);
    Map.Entry<String, AtomEntry> entry = new AbstractMap.SimpleEntry<>("k", null);

    assertThat(processor.process(entry)).isFalse();
  }

  @Test
  void throwsWhenEntryDoesNotSupportPerEntryTtl() {
    SetProcessor processor = new SetProcessor(VALUE, "tok", 1000L);
    Map.Entry<String, AtomEntry> entry =
        new AbstractMap.SimpleEntry<>("k", new AtomEntry(VALUE, "tok"));

    assertThatThrownBy(() -> processor.process(entry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not support per-entry TTL");
  }
}
