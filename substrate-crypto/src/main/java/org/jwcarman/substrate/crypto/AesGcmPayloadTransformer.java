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

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.jwcarman.substrate.core.transform.PayloadTransformer;

/**
 * AES-GCM payload transformer that encrypts and decrypts payload bytes using keys resolved via a
 * {@link SecretKeyResolver}. The wire format is {@code [kid(4) | nonce(12) | ciphertext + GCM
 * tag]}.
 *
 * <p>Thread-safe: each call creates a fresh {@link Cipher} instance.
 */
public class AesGcmPayloadTransformer implements PayloadTransformer {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int NONCE_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final int KID_LEN = 4;

  private final SecretKeyResolver resolver;
  private final SecureRandom rng = new SecureRandom();

  /**
   * Creates a new transformer backed by the given key resolver.
   *
   * @param resolver provides encryption keys by kid
   */
  public AesGcmPayloadTransformer(SecretKeyResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public byte[] encode(byte[] plaintext) {
    int kid = resolver.currentKid();
    SecretKey key = resolver.lookup(kid);
    byte[] nonce = new byte[NONCE_LEN];
    rng.nextBytes(nonce);
    try {
      Cipher c = Cipher.getInstance(ALGORITHM);
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ct = c.doFinal(plaintext);
      ByteBuffer buf = ByteBuffer.allocate(KID_LEN + NONCE_LEN + ct.length);
      buf.putInt(kid).put(nonce).put(ct);
      return buf.array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM encrypt failed", e);
    }
  }

  @Override
  public byte[] decode(byte[] ciphertext) {
    if (ciphertext.length < KID_LEN + NONCE_LEN) {
      throw new IllegalArgumentException(
          "Ciphertext too short to contain kid + nonce: " + ciphertext.length + " bytes");
    }
    ByteBuffer buf = ByteBuffer.wrap(ciphertext);
    int kid = buf.getInt();
    SecretKey key = resolver.lookup(kid);
    byte[] nonce = new byte[NONCE_LEN];
    buf.get(nonce);
    try {
      Cipher c = Cipher.getInstance(ALGORITHM);
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return c.doFinal(ciphertext, buf.position(), buf.remaining());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM decrypt failed for kid " + kid, e);
    }
  }
}
