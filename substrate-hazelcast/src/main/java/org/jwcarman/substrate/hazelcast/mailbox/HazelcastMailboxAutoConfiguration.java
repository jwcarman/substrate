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
package org.jwcarman.substrate.hazelcast.mailbox;

import com.hazelcast.core.HazelcastInstance;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.mailbox.MailboxSpi;
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
    prefix = "substrate.hazelcast.mailbox",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class HazelcastMailboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(MailboxSpi.class)
  public HazelcastMailboxSpi hazelcastMailbox(
      HazelcastInstance hazelcastInstance, HazelcastProperties properties) {
    return new HazelcastMailboxSpi(
        hazelcastInstance, properties.mailbox().prefix(), properties.mailbox().mapName());
  }
}
