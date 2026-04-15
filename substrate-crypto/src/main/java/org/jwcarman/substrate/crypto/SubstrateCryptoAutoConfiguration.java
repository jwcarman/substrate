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

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for AES-GCM payload encryption.
 *
 * <p>Activation matrix:
 *
 * <ul>
 *   <li>No shared-key, no user resolver, no user transformer → {@code PayloadTransformer.IDENTITY}
 *       from substrate-core
 *   <li>{@code substrate.crypto.shared-key} set, no user resolver → shared resolver + AES-GCM
 *       transformer
 *   <li>User {@link SecretKeyResolver} bean, no user transformer → AES-GCM transformer wrapping the
 *       user's resolver
 *   <li>User {@link PayloadTransformer} bean → user's transformer wins
 * </ul>
 */
@AutoConfiguration
@AutoConfigureBefore(SubstrateAutoConfiguration.class)
@EnableConfigurationProperties(SubstrateCryptoProperties.class)
public class SubstrateCryptoAutoConfiguration {

  private static final Log log = LogFactory.getLog(SubstrateCryptoAutoConfiguration.class);

  @Bean
  @ConditionalOnProperty("substrate.crypto.shared-key")
  @ConditionalOnMissingBean(SecretKeyResolver.class)
  public SecretKeyResolver sharedKeyResolver(SubstrateCryptoProperties props) {
    byte[] raw = Base64.getDecoder().decode(props.sharedKey());
    validateAesKeyLength(raw.length);
    log.info(
        "Substrate encryption-at-rest enabled: AES-"
            + (raw.length * 8)
            + "-GCM with shared key (kid="
            + props.sharedKid()
            + ")");
    return SecretKeyResolver.shared(new SecretKeySpec(raw, "AES"), props.sharedKid());
  }

  @Bean
  @ConditionalOnBean(SecretKeyResolver.class)
  @ConditionalOnMissingBean(PayloadTransformer.class)
  public PayloadTransformer aesGcmPayloadTransformer(SecretKeyResolver resolver) {
    return new AesGcmPayloadTransformer(resolver);
  }

  private static void validateAesKeyLength(int bytes) {
    if (bytes != 16 && bytes != 32) {
      throw new IllegalStateException(
          "substrate.crypto.shared-key must decode to 16 bytes (AES-128) or 32 bytes (AES-256); "
              + "got "
              + bytes
              + " bytes");
    }
  }
}
