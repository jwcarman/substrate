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
package org.jwcarman.substrate.hazelcast.atom;

import com.hazelcast.core.HazelcastInstance;
import org.jwcarman.substrate.core.atom.AtomSpi;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.hazelcast.HazelcastAutoConfiguration;
import org.jwcarman.substrate.hazelcast.HazelcastProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
    after = HazelcastAutoConfiguration.class,
    before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(HazelcastInstance.class)
@ConditionalOnProperty(
    prefix = "substrate.hazelcast.atom",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class HazelcastAtomAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(AtomSpi.class)
  public HazelcastAtomSpi hazelcastAtom(
      HazelcastInstance hazelcastInstance, HazelcastProperties properties) {
    return new HazelcastAtomSpi(
        hazelcastInstance, properties.atom().prefix(), properties.atom().mapName());
  }
}
