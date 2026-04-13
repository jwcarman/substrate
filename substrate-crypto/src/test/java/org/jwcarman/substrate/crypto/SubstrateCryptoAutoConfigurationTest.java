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

import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class SubstrateCryptoAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  SubstrateAutoConfiguration.class, SubstrateCryptoAutoConfiguration.class));

  @Test
  void noPropertyNoResolverNoTransformerBean() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(SecretKeyResolver.class);
          assertThat(context).hasSingleBean(PayloadTransformer.class);
          assertThat(context.getBean(PayloadTransformer.class))
              .isSameAs(PayloadTransformer.IDENTITY);
        });
  }

  @Test
  void sharedKeyOnlyCreatesResolverAndTransformer() throws Exception {
    String b64Key = generateBase64Key(256);
    contextRunner
        .withPropertyValues("substrate.crypto.shared-key=" + b64Key)
        .run(
            context -> {
              assertThat(context).hasSingleBean(SecretKeyResolver.class);
              assertThat(context).hasSingleBean(PayloadTransformer.class);
              assertThat(context.getBean(PayloadTransformer.class))
                  .isInstanceOf(AesGcmPayloadTransformer.class);
            });
  }

  @Test
  void userResolverBeanWithNoSharedKeyCreatesTransformer() {
    contextRunner
        .withUserConfiguration(CustomResolverConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(SecretKeyResolver.class);
              assertThat(context).hasSingleBean(PayloadTransformer.class);
              assertThat(context.getBean(PayloadTransformer.class))
                  .isInstanceOf(AesGcmPayloadTransformer.class);
            });
  }

  @Test
  void userTransformerBeanWins() {
    contextRunner
        .withUserConfiguration(CustomTransformerConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(PayloadTransformer.class);
              assertThat(context.getBean(PayloadTransformer.class))
                  .isInstanceOf(StubPayloadTransformer.class);
            });
  }

  @Test
  void invalidKeySizeFailsFast() {
    String badKey = Base64.getEncoder().encodeToString(new byte[24]);
    contextRunner
        .withPropertyValues("substrate.crypto.shared-key=" + badKey)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("16 bytes (AES-128) or 32 bytes (AES-256)")
                    .hasMessageContaining("got 24 bytes"));
  }

  @Test
  void aes128KeyIsAccepted() throws Exception {
    String b64Key = generateBase64Key(128);
    contextRunner
        .withPropertyValues("substrate.crypto.shared-key=" + b64Key)
        .run(
            context -> {
              assertThat(context).hasSingleBean(PayloadTransformer.class);
              assertThat(context.getBean(PayloadTransformer.class))
                  .isInstanceOf(AesGcmPayloadTransformer.class);
            });
  }

  private static String generateBase64Key(int bits) throws Exception {
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(bits);
    SecretKey key = gen.generateKey();
    return Base64.getEncoder().encodeToString(key.getEncoded());
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomResolverConfiguration {

    @Bean
    SecretKeyResolver customResolver() throws Exception {
      KeyGenerator gen = KeyGenerator.getInstance("AES");
      gen.init(256);
      return SecretKeyResolver.shared(gen.generateKey(), 5);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomTransformerConfiguration {

    @Bean
    PayloadTransformer customTransformer() {
      return new StubPayloadTransformer();
    }
  }

  static class StubPayloadTransformer implements PayloadTransformer {

    @Override
    public byte[] encode(byte[] plaintext) {
      return plaintext;
    }

    @Override
    public byte[] decode(byte[] ciphertext) {
      return ciphertext;
    }
  }
}
