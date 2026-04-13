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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AesGcmPayloadTransformerTest {

  private SecretKey key;
  private AesGcmPayloadTransformer transformer;

  @BeforeEach
  void setUp() throws Exception {
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256);
    key = gen.generateKey();
    transformer = new AesGcmPayloadTransformer(SecretKeyResolver.shared(key));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 1024, 10240})
  void roundTripsVariousPayloadSizes(int size) {
    byte[] plaintext = new byte[size];
    Arrays.fill(plaintext, (byte) 0xAB);

    byte[] ciphertext = transformer.encode(plaintext);
    byte[] decoded = transformer.decode(ciphertext);

    assertThat(decoded).isEqualTo(plaintext);
  }

  @Test
  void encodingTheSamePlaintextTwiceProducesDifferentCiphertext() {
    byte[] plaintext = "hello world".getBytes();

    byte[] ct1 = transformer.encode(plaintext);
    byte[] ct2 = transformer.encode(plaintext);

    assertThat(ct1).isNotEqualTo(ct2);
  }

  @Test
  void decodingWithUnknownKidThrowsIllegalStateException() {
    byte[] plaintext = "test".getBytes();
    byte[] ciphertext = transformer.encode(plaintext);

    SecretKeyResolver otherResolver = SecretKeyResolver.shared(key, 99);
    AesGcmPayloadTransformer otherTransformer = new AesGcmPayloadTransformer(otherResolver);

    assertThatThrownBy(() -> otherTransformer.decode(ciphertext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown kid 0");
  }

  @Test
  void decodingTruncatedCiphertextThrowsIllegalArgumentException() {
    byte[] tooShort = new byte[10];

    assertThatThrownBy(() -> transformer.decode(tooShort))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ciphertext too short")
        .hasMessageContaining("10 bytes");
  }

  @Test
  void decodingTamperedCiphertextThrowsIllegalStateException() {
    byte[] plaintext = "sensitive data".getBytes();
    byte[] ciphertext = transformer.encode(plaintext);

    ciphertext[ciphertext.length - 1] ^= 0xFF;

    byte[] tampered = ciphertext;
    assertThatThrownBy(() -> transformer.decode(tampered))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AES-GCM decrypt failed");
  }

  @Test
  void rotationRoundTrip() throws Exception {
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256);
    SecretKey key7 = gen.generateKey();
    SecretKey key8 = gen.generateKey();

    Map<Integer, SecretKey> keys = new ConcurrentHashMap<>();
    keys.put(7, key7);
    keys.put(8, key8);
    AtomicInteger currentKid = new AtomicInteger(7);

    SecretKeyResolver rotatingResolver =
        new SecretKeyResolver() {
          @Override
          public int currentKid() {
            return currentKid.get();
          }

          @Override
          public SecretKey lookup(int kid) {
            SecretKey k = keys.get(kid);
            if (k == null) {
              throw new IllegalArgumentException("Unknown kid " + kid);
            }
            return k;
          }
        };

    AesGcmPayloadTransformer rotatingTransformer = new AesGcmPayloadTransformer(rotatingResolver);

    byte[] plaintext = "rotate me".getBytes();
    byte[] ct7 = rotatingTransformer.encode(plaintext);

    currentKid.set(8);
    byte[] ct8 = rotatingTransformer.encode(plaintext);

    assertThat(rotatingTransformer.decode(ct7)).isEqualTo(plaintext);
    assertThat(rotatingTransformer.decode(ct8)).isEqualTo(plaintext);
  }
}
