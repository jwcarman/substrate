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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the substrate-crypto module.
 *
 * @param sharedKey base64-encoded AES key (16 or 32 bytes raw), or {@code null} if the user
 *     provides their own {@link SecretKeyResolver} bean
 * @param sharedKid kid assigned to the shared key (defaults to 0)
 */
@ConfigurationProperties(prefix = "substrate.crypto")
public record SubstrateCryptoProperties(String sharedKey, Integer sharedKid) {

  public SubstrateCryptoProperties {
    if (sharedKid == null) {
      sharedKid = 0;
    }
  }
}
