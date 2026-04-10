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
package org.jwcarman.substrate.redis.mailbox;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.mailbox.MailboxSpi;
import org.jwcarman.substrate.redis.RedisAutoConfiguration;
import org.jwcarman.substrate.redis.RedisProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@AutoConfiguration(after = RedisAutoConfiguration.class, before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(RedisConnectionFactory.class)
@ConditionalOnProperty(
    prefix = "substrate.redis.mailbox",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RedisMailboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(MailboxSpi.class)
  public RedisMailboxSpi redisMailbox(
      RedisConnectionFactory connectionFactory, RedisProperties properties) {
    LettuceConnectionFactory lcf = (LettuceConnectionFactory) connectionFactory;
    RedisClient client = (RedisClient) lcf.getNativeClient();
    StatefulRedisConnection<String, String> connection = client.connect(StringCodec.UTF8);

    return new RedisMailboxSpi(connection.sync(), properties.mailbox().prefix());
  }
}
