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
package org.jwcarman.substrate.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.redis.atom.RedisAtomAutoConfiguration;
import org.jwcarman.substrate.redis.atom.RedisAtomSpi;
import org.jwcarman.substrate.redis.journal.RedisJournalAutoConfiguration;
import org.jwcarman.substrate.redis.journal.RedisJournalSpi;
import org.jwcarman.substrate.redis.mailbox.RedisMailboxAutoConfiguration;
import org.jwcarman.substrate.redis.mailbox.RedisMailboxSpi;
import org.jwcarman.substrate.redis.notifier.RedisNotifierAutoConfiguration;
import org.jwcarman.substrate.redis.notifier.RedisNotifierSpi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class RedisDisablePropertyTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  RedisAutoConfiguration.class,
                  RedisAtomAutoConfiguration.class,
                  RedisJournalAutoConfiguration.class,
                  RedisMailboxAutoConfiguration.class,
                  RedisNotifierAutoConfiguration.class))
          .withUserConfiguration(MockRedisConfiguration.class);

  @Test
  void disablingAtomPreventsAtomBean() {
    contextRunner
        .withPropertyValues("substrate.redis.atom.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RedisAtomSpi.class));
  }

  @Test
  void disablingJournalPreventsJournalBean() {
    contextRunner
        .withPropertyValues("substrate.redis.journal.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RedisJournalSpi.class));
  }

  @Test
  void disablingMailboxPreventsMailboxBean() {
    contextRunner
        .withPropertyValues("substrate.redis.mailbox.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RedisMailboxSpi.class));
  }

  @Test
  void disablingNotifierPreventsNotifierBean() {
    contextRunner
        .withPropertyValues("substrate.redis.notifier.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RedisNotifierSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockRedisConfiguration {

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
      RedisClient client = mock(RedisClient.class);
      StatefulRedisConnection<String, String> stringConnection = mock();
      RedisCommands<String, String> stringCommands = mock();
      StatefulRedisConnection<byte[], byte[]> byteConnection = mock();
      RedisCommands<byte[], byte[]> byteCommands = mock();
      StatefulRedisPubSubConnection<byte[], byte[]> pubSubConnection = mock();
      RedisPubSubCommands<byte[], byte[]> pubSubCommands = mock();

      when(factory.getNativeClient()).thenReturn(client);
      when(client.connect(StringCodec.UTF8)).thenReturn(stringConnection);
      when(stringConnection.sync()).thenReturn(stringCommands);
      when(client.connect(ByteArrayCodec.INSTANCE)).thenReturn(byteConnection);
      when(byteConnection.sync()).thenReturn(byteCommands);
      when(client.connectPubSub(ByteArrayCodec.INSTANCE)).thenReturn(pubSubConnection);
      when(pubSubConnection.sync()).thenReturn(pubSubCommands);

      return factory;
    }
  }
}
