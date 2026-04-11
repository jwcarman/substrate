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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.sns.SnsAutoConfiguration;
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
  void createsSnsNotifierSpiBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SnsAutoConfiguration.class, SnsNotifierAutoConfiguration.class))
        .withUserConfiguration(MockAwsConfiguration.class)
        .withPropertyValues("substrate.sns.notifier.topic-arn=arn:aws:sns:us-east-1:123456789:test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SnsNotifierSpi.class);
              assertThat(context).hasSingleBean(NotifierSpi.class);
            });
  }

  @Test
  void usesExistingSnsClientBeanWhenProvided() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SnsAutoConfiguration.class, SnsNotifierAutoConfiguration.class))
        .withUserConfiguration(MockAwsConfiguration.class)
        .withPropertyValues("substrate.sns.notifier.topic-arn=arn:aws:sns:us-east-1:123456789:test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SnsClient.class);
              // The bean should be the one from MockAwsConfiguration, not auto-created
              assertThat(context.getBean(SnsClient.class)).isNotNull();
            });
  }

  @Test
  void usesExistingSqsClientBeanWhenProvided() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SnsAutoConfiguration.class, SnsNotifierAutoConfiguration.class))
        .withUserConfiguration(MockAwsConfiguration.class)
        .withPropertyValues("substrate.sns.notifier.topic-arn=arn:aws:sns:us-east-1:123456789:test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SqsClient.class);
              // The bean should be the one from MockAwsConfiguration, not auto-created
              assertThat(context.getBean(SqsClient.class)).isNotNull();
            });
  }

  @Test
  void autoCreatesTopicWhenAutoCreateTopicEnabledAndNoTopicArn() {
    SnsClient mockSnsClient = mock(SnsClient.class);
    when(mockSnsClient.createTopic(any(java.util.function.Consumer.class)))
        .thenReturn(
            software.amazon.awssdk.services.sns.model.CreateTopicResponse.builder()
                .topicArn("arn:aws:sns:us-east-1:123456789:substrate-notifications")
                .build());
    when(mockSnsClient.subscribe(any(SubscribeRequest.class)))
        .thenReturn(
            SubscribeResponse.builder()
                .subscriptionArn("arn:aws:sns:us-east-1:123456789:test:sub-123")
                .build());

    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SnsAutoConfiguration.class, SnsNotifierAutoConfiguration.class))
        .withBean(SnsClient.class, () -> mockSnsClient)
        .withUserConfiguration(MockSqsOnlyConfiguration.class)
        .withPropertyValues("substrate.sns.notifier.auto-create-topic=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SnsNotifierSpi.class);
            });
  }

  @Test
  void doesNotCreateBeansWhenDisabledViaProperty() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SnsAutoConfiguration.class, SnsNotifierAutoConfiguration.class))
        .withUserConfiguration(MockAwsConfiguration.class)
        .withPropertyValues("substrate.sns.notifier.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(SnsNotifierSpi.class);
            });
  }

  @Test
  void snsNotifierSpiSuppressesInMemoryFallback() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SnsAutoConfiguration.class,
                SnsNotifierAutoConfiguration.class,
                SubstrateAutoConfiguration.class))
        .withUserConfiguration(MockAwsConfiguration.class)
        .withPropertyValues("substrate.sns.notifier.topic-arn=arn:aws:sns:us-east-1:123456789:test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(NotifierSpi.class);
              assertThat(context.getBean(NotifierSpi.class)).isInstanceOf(SnsNotifierSpi.class);
              assertThat(context).doesNotHaveBean(InMemoryNotifier.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MockSqsOnlyConfiguration {

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
