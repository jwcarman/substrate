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

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class SecretKeyResolverTest {

  private static SecretKey generateKey() throws Exception {
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256);
    return gen.generateKey();
  }

  @Test
  void sharedResolverReturnsKeyForDefaultKid() throws Exception {
    SecretKey key = generateKey();
    SecretKeyResolver resolver = SecretKeyResolver.shared(key);

    assertThat(resolver.currentKid()).isZero();
    assertThat(resolver.lookup(0)).isSameAs(key);
  }

  @Test
  void sharedResolverReturnsKeyForSpecifiedKid() throws Exception {
    SecretKey key = generateKey();
    SecretKeyResolver resolver = SecretKeyResolver.shared(key, 42);

    assertThat(resolver.currentKid()).isEqualTo(42);
    assertThat(resolver.lookup(42)).isSameAs(key);
  }

  @Test
  void sharedResolverThrowsForUnknownKid() throws Exception {
    SecretKey key = generateKey();
    SecretKeyResolver resolver = SecretKeyResolver.shared(key);

    assertThatThrownBy(() -> resolver.lookup(99))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown kid 99")
        .hasMessageContaining("only serves kid 0");
  }

  @Test
  void sharedResolverWithCustomKidThrowsForOtherKids() throws Exception {
    SecretKey key = generateKey();
    SecretKeyResolver resolver = SecretKeyResolver.shared(key, 7);

    assertThatThrownBy(() -> resolver.lookup(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown kid 0")
        .hasMessageContaining("only serves kid 7");
  }
}
