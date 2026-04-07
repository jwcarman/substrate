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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.memory.InMemoryNotifier;
import org.jwcarman.substrate.spi.Notifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesResponse;

class SnsNotifierAutoConfigurationTest {

  @Test
  void createsSnsNotifierBean() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SnsNotifierAutoConfiguration.class))
        .withUserConfiguration(MockAwsConfiguration.class)
        .withPropertyValues("substrate.notifier.sns.topic-arn=arn:aws:sns:us-east-1:123456789:test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SnsNotifier.class);
              assertThat(context).hasSingleBean(Notifier.class);
            });
  }

  @Test
  void snsNotifierSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SnsNotifierAutoConfiguration.class, SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockAwsConfiguration.class)
        .withPropertyValues("substrate.notifier.sns.topic-arn=arn:aws:sns:us-east-1:123456789:test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(Notifier.class);
              assertThat(context.getBean(Notifier.class)).isInstanceOf(SnsNotifier.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockAwsConfiguration {

    @Bean
    SnsClient snsClient() {
      SnsClient client = mock(SnsClient.class);
      when(client.subscribe(any(SubscribeRequest.class)))
          .thenReturn(
              SubscribeResponse.builder()
                  .subscriptionArn("arn:aws:sns:us-east-1:123456789:test:sub-123")
                  .build());
      return client;
    }

    @Bean
    SqsClient sqsClient() {
      SqsClient client = mock(SqsClient.class);
      when(client.createQueue(any(CreateQueueRequest.class)))
          .thenReturn(
              CreateQueueResponse.builder()
                  .queueUrl("http://localhost:4566/000000000000/test-queue")
                  .build());
      when(client.getQueueAttributes(any(GetQueueAttributesRequest.class)))
          .thenReturn(
              GetQueueAttributesResponse.builder()
                  .attributes(
                      Map.of(
                          QueueAttributeName.QUEUE_ARN,
                          "arn:aws:sqs:us-east-1:000000000000:test-queue"))
                  .build());
      when(client.setQueueAttributes(any(SetQueueAttributesRequest.class)))
          .thenReturn(SetQueueAttributesResponse.builder().build());
      when(client.receiveMessage(any(ReceiveMessageRequest.class)))
          .thenReturn(ReceiveMessageResponse.builder().build());
      return client;
    }
  }
}
