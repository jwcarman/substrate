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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Testcontainers
class SnsNotifierIT {

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
          .withServices("sns", "sqs");

  private SnsClient snsClient;
  private SqsClient sqsClient;
  private String topicArn;
  private SnsNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));
    Region region = Region.of(localstack.getRegion());

    snsClient =
        SnsClient.builder()
            .endpointOverride(localstack.getEndpoint())
            .credentialsProvider(credentials)
            .region(region)
            .build();

    sqsClient =
        SqsClient.builder()
            .endpointOverride(localstack.getEndpoint())
            .credentialsProvider(credentials)
            .region(region)
            .build();

    topicArn = snsClient.createTopic(r -> r.name("substrate-test")).topicArn();
    notifier = new SnsNotifierSpi(snsClient, sqsClient, topicArn, 60, 1);
  }

  @AfterEach
  void tearDown() {
    if (notifier.isRunning()) {
      notifier.stop();
    }
    if (snsClient != null) {
      snsClient.close();
    }
    if (sqsClient != null) {
      sqsClient.close();
    }
  }

  @Test
  void notifyAndSubscribeRoundTrip() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<String> receivedKeys = new CopyOnWriteArrayList<>();
    List<String> receivedPayloads = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          receivedKeys.add(key);
          receivedPayloads.add(payload);
          latch.countDown();
        });
    notifier.start();

    notifier.notify("test:key", "test-payload");

    assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedKeys).containsExactly("test:key");
    assertThat(receivedPayloads).containsExactly("test-payload");
  }

  @Test
  void multipleHandlersAllReceiveNotifications() throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    List<String> handler1Received = new CopyOnWriteArrayList<>();
    List<String> handler2Received = new CopyOnWriteArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          handler1Received.add(payload);
          latch.countDown();
        });
    notifier.subscribe(
        (key, payload) -> {
          handler2Received.add(payload);
          latch.countDown();
        });
    notifier.start();

    notifier.notify("key", "value");

    assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(handler1Received).containsExactly("value");
    assertThat(handler2Received).containsExactly("value");
  }

  @Test
  void lifecycleStartAndStopWithCleanup() {
    notifier.subscribe((key, payload) -> {});
    notifier.start();
    assertThat(notifier.isRunning()).isTrue();

    notifier.stop();
    assertThat(notifier.isRunning()).isFalse();
  }
}
