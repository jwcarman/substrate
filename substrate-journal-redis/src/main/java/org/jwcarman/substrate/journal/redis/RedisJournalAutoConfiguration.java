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
package org.jwcarman.substrate.journal.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@AutoConfiguration(before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(RedisConnectionFactory.class)
@EnableConfigurationProperties(RedisJournalProperties.class)
@PropertySource("classpath:substrate-journal-redis-defaults.properties")
public class RedisJournalAutoConfiguration {

  @Bean
  public RedisJournal redisJournal(
      RedisConnectionFactory connectionFactory, RedisJournalProperties properties) {
    LettuceConnectionFactory lcf = (LettuceConnectionFactory) connectionFactory;
    RedisClient client = (RedisClient) lcf.getNativeClient();
    StatefulRedisConnection<String, String> connection = client.connect(StringCodec.UTF8);

    return new RedisJournal(
        connection.sync(), properties.prefix(), properties.maxLen(), properties.defaultTtl());
  }
}
