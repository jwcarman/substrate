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
package org.jwcarman.substrate.redis.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class RedisAtomAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(RedisAutoConfiguration.class, RedisAtomAutoConfiguration.class))
          .withUserConfiguration(MockRedisConfiguration.class);

  @Test
  void registersRedisAtomSpiBean() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(RedisAtomSpi.class));
  }

  @Test
  void disabledPropertyPreventsBean() {
    contextRunner
        .withPropertyValues("substrate.redis.atom.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RedisAtomSpi.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class MockRedisConfiguration {

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
      RedisClient client = mock(RedisClient.class);
      StatefulRedisConnection<String, String> connection = mock();
      RedisCommands<String, String> commands = mock();

      when(factory.getNativeClient()).thenReturn(client);
      when(client.connect(StringCodec.UTF8)).thenReturn(connection);
      when(connection.sync()).thenReturn(commands);

      return factory;
    }
  }
}
