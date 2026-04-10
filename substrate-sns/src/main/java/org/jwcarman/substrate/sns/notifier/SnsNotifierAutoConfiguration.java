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
package org.jwcarman.substrate.sns.notifier;

import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.sns.SnsAutoConfiguration;
import org.jwcarman.substrate.sns.SnsProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@AutoConfiguration(after = SnsAutoConfiguration.class, before = SubstrateAutoConfiguration.class)
@ConditionalOnClass(SnsClient.class)
@ConditionalOnProperty(
    prefix = "substrate.sns.notifier",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
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
  @ConditionalOnMissingBean(SnsNotifierSpi.class)
  public SnsNotifierSpi snsNotifierSpi(
      SnsClient snsClient, SqsClient sqsClient, SnsProperties properties) {
    SnsProperties.NotifierProperties notifier = properties.notifier();
    String topicArn = notifier.topicArn();
    if (notifier.autoCreateTopic() && topicArn == null) {
      topicArn =
          snsClient.createTopic(request -> request.name("substrate-notifications")).topicArn();
    }
    return new SnsNotifierSpi(
        snsClient,
        sqsClient,
        topicArn,
        (int) notifier.sqsMessageRetention().toSeconds(),
        notifier.sqsWaitTimeSeconds());
  }
}
