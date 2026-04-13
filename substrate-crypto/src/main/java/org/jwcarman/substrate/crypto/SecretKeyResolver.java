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
package org.jwcarman.substrate.crypto;

import javax.crypto.SecretKey;

/**
 * Resolves encryption keys by their integer key ID (kid). Implementations back this interface with
 * a KMS, Vault, keystore, or a simple in-memory map.
 *
 * <p>The resolver is called on both the encode path (via {@link #currentKid()}) and the decode path
 * (for any kid embedded in existing ciphertext). Implementations MUST be able to resolve every kid
 * that appears in stored data — retiring a kid makes all blobs encrypted under that kid permanently
 * unreadable.
 */
public interface SecretKeyResolver {

  /**
   * The key ID to use for new encryptions. Called once per encode. Implementations that never
   * rotate may return a fixed value.
   */
  int currentKid();

  /**
   * Looks up a key by its ID. Called on both the encode path (via {@link #currentKid()}) and the
   * decode path (for any kid embedded in existing ciphertext).
   *
   * @param kid the key identifier
   * @return the secret key for the given kid
   * @throws IllegalArgumentException if the kid is unknown
   */
  SecretKey lookup(int kid);

  /**
   * Returns a resolver that serves a single key at kid 0.
   *
   * @param key the secret key
   * @return a fixed-kid resolver
   */
  static SecretKeyResolver shared(SecretKey key) {
    return shared(key, 0);
  }

  /**
   * Returns a resolver that serves a single key at the given kid.
   *
   * @param key the secret key
   * @param kid the key identifier
   * @return a fixed-kid resolver
   */
  static SecretKeyResolver shared(SecretKey key, int kid) {
    return new SecretKeyResolver() {
      @Override
      public int currentKid() {
        return kid;
      }

      @Override
      public SecretKey lookup(int k) {
        if (k != kid) {
          throw new IllegalArgumentException(
              "Unknown kid " + k + "; shared resolver only serves kid " + kid);
        }
        return key;
      }
    };
  }
}
