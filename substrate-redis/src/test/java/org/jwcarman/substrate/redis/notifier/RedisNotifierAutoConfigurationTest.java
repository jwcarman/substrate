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
package org.jwcarman.substrate.redis.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class RedisNotifierAutoConfigurationTest {

  private static LettuceConnectionFactory createMockConnectionFactory() {
    LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
    RedisClient client = mock(RedisClient.class);
    StatefulRedisConnection<String, String> connection = mock();
    RedisCommands<String, String> commands = mock();
    StatefulRedisPubSubConnection<String, String> pubSubConnection = mock();
    RedisPubSubCommands<String, String> pubSubCommands = mock();

    when(factory.getNativeClient()).thenReturn(client);
    when(client.connect(StringCodec.UTF8)).thenReturn(connection);
    when(connection.sync()).thenReturn(commands);
    when(client.connectPubSub(StringCodec.UTF8)).thenReturn(pubSubConnection);
    when(pubSubConnection.sync()).thenReturn(pubSubCommands);

    return factory;
  }

  @Test
  void createsRedisNotifierSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RedisAutoConfiguration.class, RedisNotifierAutoConfiguration.class))
        .withUserConfiguration(MockRedisConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RedisNotifierSpi.class);
              assertThat(context).hasSingleBean(NotifierSpi.class);
            });
  }

  @Test
  void redisNotifierSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RedisAutoConfiguration.class,
                RedisNotifierAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockRedisConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(NotifierSpi.class);
              assertThat(context.getBean(NotifierSpi.class)).isInstanceOf(RedisNotifierSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockRedisConfiguration {

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      return createMockConnectionFactory();
    }
  }
}
