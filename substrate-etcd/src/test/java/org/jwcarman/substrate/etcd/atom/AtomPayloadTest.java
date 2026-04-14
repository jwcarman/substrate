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
package org.jwcarman.substrate.etcd.atom;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.atom.RawAtom;

class AtomPayloadTest {

  @Test
  void roundTripPreservesValueAndToken() {
    byte[] value = "hello, world".getBytes(StandardCharsets.UTF_8);
    String token = "token-abc";

    byte[] encoded = AtomPayload.encode(value, token);
    RawAtom decoded = AtomPayload.decode(encoded);

    assertThat(decoded.value()).isEqualTo(value);
    assertThat(decoded.token()).isEqualTo(token);
  }

  @Test
  void roundTripHandlesEmptyValue() {
    byte[] value = new byte[0];
    String token = "tok";

    RawAtom decoded = AtomPayload.decode(AtomPayload.encode(value, token));

    assertThat(decoded.value()).isEmpty();
    assertThat(decoded.token()).isEqualTo(token);
  }

  @Test
  void roundTripHandlesEmptyToken() {
    byte[] value = "data".getBytes(StandardCharsets.UTF_8);

    RawAtom decoded = AtomPayload.decode(AtomPayload.encode(value, ""));

    assertThat(decoded.value()).isEqualTo(value);
    assertThat(decoded.token()).isEmpty();
  }

  @Test
  void roundTripHandlesBinaryValueWithZeroBytes() {
    byte[] value = new byte[] {0, 1, 2, 3, 0, (byte) 255};
    String token = "t";

    RawAtom decoded = AtomPayload.decode(AtomPayload.encode(value, token));

    assertThat(decoded.value()).isEqualTo(value);
    assertThat(decoded.token()).isEqualTo(token);
  }
}
