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
package org.jwcarman.substrate.notifier.sns;

import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@AutoConfiguration(before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(SnsClient.class)
@EnableConfigurationProperties(SnsNotifierProperties.class)
@PropertySource("classpath:substrate-notifier-sns-defaults.properties")
public class SnsNotifierAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(SnsClient.class)
  public SnsClient snsClient() {
    return SnsClient.create();
  }

  @Bean
  @ConditionalOnMissingBean(SqsClient.class)
  public SqsClient sqsClient() {
    return SqsClient.create();
  }

  @Bean
  public SnsNotifier snsNotifier(
      SnsClient snsClient, SqsClient sqsClient, SnsNotifierProperties properties) {
    String topicArn = properties.topicArn();
    if (properties.autoCreateTopic() && topicArn == null) {
      topicArn =
          snsClient.createTopic(request -> request.name("substrate-notifications")).topicArn();
    }
    return new SnsNotifier(
        snsClient,
        sqsClient,
        topicArn,
        (int) properties.sqsMessageRetention().toSeconds(),
        properties.sqsWaitTimeSeconds());
  }
}
