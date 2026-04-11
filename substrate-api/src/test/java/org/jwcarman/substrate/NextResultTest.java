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
package org.jwcarman.substrate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NextResultTest {

  @Test
  void valueIsNotTerminal() {
    assertThat(new NextResult.Value<>("v").isTerminal()).isFalse();
  }

  @Test
  void timeoutIsNotTerminal() {
    assertThat(new NextResult.Timeout<>().isTerminal()).isFalse();
  }

  @Test
  void completedIsTerminal() {
    assertThat(new NextResult.Completed<>().isTerminal()).isTrue();
  }

  @Test
  void expiredIsTerminal() {
    assertThat(new NextResult.Expired<>().isTerminal()).isTrue();
  }

  @Test
  void deletedIsTerminal() {
    assertThat(new NextResult.Deleted<>().isTerminal()).isTrue();
  }

  @Test
  void erroredIsTerminal() {
    assertThat(new NextResult.Errored<>(new RuntimeException("boom")).isTerminal()).isTrue();
  }
}
