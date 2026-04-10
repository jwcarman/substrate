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
package org.jwcarman.substrate.redis.atom;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jwcarman.substrate.core.atom.RawAtom;

final class AtomPayload {

  private AtomPayload() {}

  static String encode(byte[] value, String token) {
    byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buf = ByteBuffer.allocate(4 + tokenBytes.length + value.length);
    buf.putInt(tokenBytes.length);
    buf.put(tokenBytes);
    buf.put(value);
    return Base64.getEncoder().encodeToString(buf.array());
  }

  static RawAtom decode(String encoded) {
    byte[] payload = Base64.getDecoder().decode(encoded);
    ByteBuffer buf = ByteBuffer.wrap(payload);
    int tokenLen = buf.getInt();
    byte[] tokenBytes = new byte[tokenLen];
    buf.get(tokenBytes);
    byte[] valueBytes = new byte[buf.remaining()];
    buf.get(valueBytes);
    return new RawAtom(valueBytes, new String(tokenBytes, StandardCharsets.UTF_8));
  }
}
