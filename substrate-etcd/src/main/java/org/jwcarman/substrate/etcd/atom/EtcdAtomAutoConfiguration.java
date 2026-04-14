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
package org.jwcarman.substrate.etcd.atom;

import io.etcd.jetcd.Client;
import org.jwcarman.substrate.core.atom.AtomSpi;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.etcd.EtcdAutoConfiguration;
import org.jwcarman.substrate.etcd.EtcdProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = EtcdAutoConfiguration.class, before = SubstrateAutoConfiguration.class)
@ConditionalOnBean(Client.class)
@ConditionalOnProperty(
    prefix = "substrate.etcd.atom",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class EtcdAtomAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(AtomSpi.class)
  public EtcdAtomSpi etcdAtomSpi(Client client, EtcdProperties properties) {
    return new EtcdAtomSpi(client, properties.atom().prefix());
  }
}
