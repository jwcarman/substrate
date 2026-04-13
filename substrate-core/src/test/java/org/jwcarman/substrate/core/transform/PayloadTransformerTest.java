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
package org.jwcarman.substrate.core.transform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PayloadTransformerTest {

  @Test
  void identityEncodeReturnsSameBytes() {
    byte[] input = "hello".getBytes(StandardCharsets.UTF_8);

    byte[] result = PayloadTransformer.IDENTITY.encode(input);

    assertThat(result).isSameAs(input);
  }

  @Test
  void identityDecodeReturnsSameBytes() {
    byte[] input = "hello".getBytes(StandardCharsets.UTF_8);

    byte[] result = PayloadTransformer.IDENTITY.decode(input);

    assertThat(result).isSameAs(input);
  }

  @Test
  void identityRoundTripsUnchanged() {
    byte[] input = "round-trip".getBytes(StandardCharsets.UTF_8);

    byte[] encoded = PayloadTransformer.IDENTITY.encode(input);
    byte[] decoded = PayloadTransformer.IDENTITY.decode(encoded);

    assertThat(decoded).isSameAs(input);
  }
}
