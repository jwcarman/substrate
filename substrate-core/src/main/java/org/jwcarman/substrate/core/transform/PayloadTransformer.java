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

/**
 * Transforms payload bytes on their way to and from the backend SPI. The primary use case is
 * encryption-at-rest — implementations apply encryption on {@link #encode} and decryption on {@link
 * #decode}. Other use cases include compression, integrity signing, or custom framing.
 *
 * <p>Must be deterministic in one direction: {@code decode(encode(x))} must equal {@code x} for all
 * inputs. The encode side MAY be non-deterministic (e.g. AES-GCM with a random nonce).
 *
 * <p>All methods are called on substrate primitive threads. Implementations MUST be thread-safe.
 *
 * <p>If an implementation rotates keys over time, make sure {@link #decode} can still read payloads
 * that were encoded with any historical key that may still exist in storage.
 */
public interface PayloadTransformer {

  /** Called before writing to the backend. Typical use: encrypt the bytes. */
  byte[] encode(byte[] plaintext);

  /** Called after reading from the backend. Typical use: decrypt the bytes. */
  byte[] decode(byte[] ciphertext);

  /** Pass-through transformer. The default when no concrete transformer is configured. */
  PayloadTransformer IDENTITY =
      new PayloadTransformer() {
        @Override
        public byte[] encode(byte[] plaintext) {
          return plaintext;
        }

        @Override
        public byte[] decode(byte[] ciphertext) {
          return ciphertext;
        }
      };
}
