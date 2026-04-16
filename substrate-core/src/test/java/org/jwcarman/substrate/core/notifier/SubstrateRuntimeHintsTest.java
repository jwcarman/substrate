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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;

class SubstrateRuntimeHintsTest {

  private final RuntimeHints hints = new RuntimeHints();

  {
    new SubstrateRuntimeHints().registerHints(hints, getClass().getClassLoader());
  }

  @Test
  void registersBindingHintsForRawNotification() {
    assertTypeHintRegistered(RawNotification.class);
  }

  @Test
  void registersBindingHintsForNestedPrimitiveTypeEnum() {
    assertTypeHintRegistered(PrimitiveType.class);
  }

  @Test
  void registersBindingHintsForNestedEventTypeEnum() {
    assertTypeHintRegistered(EventType.class);
  }

  private void assertTypeHintRegistered(Class<?> type) {
    assertThat(hints.reflection().typeHints())
        .as("expected binding hints for %s", type.getName())
        .anyMatch(th -> th.getType().equals(TypeReference.of(type)));
  }
}
