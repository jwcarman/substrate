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
package org.jwcarman.substrate.core.atom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RawAtomTest {

  @Test
  void equalsReturnsTrueForIdenticalContent() {
    byte[] a = new byte[] {1, 2, 3};
    byte[] b = new byte[] {1, 2, 3};
    assertThat(a).isNotSameAs(b);

    RawAtom first = new RawAtom(a, "token1");
    RawAtom second = new RawAtom(b, "token1");
    assertThat(first).isEqualTo(second);
  }

  @Test
  void equalsReturnsFalseForDifferentValue() {
    RawAtom first = new RawAtom(new byte[] {1, 2, 3}, "token1");
    RawAtom second = new RawAtom(new byte[] {4, 5, 6}, "token1");
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void equalsReturnsFalseForDifferentToken() {
    byte[] value = new byte[] {1, 2, 3};
    RawAtom first = new RawAtom(value.clone(), "token1");
    RawAtom second = new RawAtom(value.clone(), "token2");
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void hashCodeMatchesForEqualRecords() {
    byte[] a = new byte[] {10, 20, 30};
    byte[] b = new byte[] {10, 20, 30};
    RawAtom first = new RawAtom(a, "t");
    RawAtom second = new RawAtom(b, "t");
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void toStringIncludesValueAndToken() {
    RawAtom atom = new RawAtom(new byte[] {7, 8, 9}, "myToken");
    String result = atom.toString();
    assertThat(result).contains("myToken");
    assertThat(result).doesNotContain("[B@");
    assertThat(result).contains("7").contains("8").contains("9");
  }
}
