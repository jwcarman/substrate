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
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;

@ExtendWith(MockitoExtension.class)
class SnsNotifierTest {

  @Mock private SnsClient snsClient;
  @Mock private SqsClient sqsClient;

  private SnsNotifier notifier;

  @BeforeEach
  void setUp() {
    notifier =
        new SnsNotifier(snsClient, sqsClient, "arn:aws:sns:us-east-1:123456789:test", 60, 20);
  }

  @Test
  void notifyPublishesMessageWithCorrectFormat() {
    notifier.notify("my:key", "my-payload");

    ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
    verify(snsClient).publish(captor.capture());
    PublishRequest request = captor.getValue();
    assertThat(request.topicArn()).isEqualTo("arn:aws:sns:us-east-1:123456789:test");
    assertThat(request.message()).isEqualTo("my:key|my-payload");
  }

  @Test
  void parseAndDispatchExtractsKeyAndPayload() {
    List<String> receivedKeys = new ArrayList<>();
    List<String> receivedPayloads = new ArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          receivedKeys.add(key);
          receivedPayloads.add(payload);
        });

    notifier.parseAndDispatch("test:key|test-payload");

    assertThat(receivedKeys).containsExactly("test:key");
    assertThat(receivedPayloads).containsExactly("test-payload");
  }

  @Test
  void parseAndDispatchHandlesSnsEnvelope() {
    List<String> receivedKeys = new ArrayList<>();
    List<String> receivedPayloads = new ArrayList<>();

    notifier.subscribe(
        (key, payload) -> {
          receivedKeys.add(key);
          receivedPayloads.add(payload);
        });

    String snsEnvelope =
        """
        {
          "Type": "Notification",
          "MessageId": "abc-123",
          "TopicArn": "arn:aws:sns:us-east-1:123456789:test",
          "Message": "my:key|my-payload",
          "Timestamp": "2026-01-01T00:00:00.000Z"
        }
        """;

    notifier.parseAndDispatch(snsEnvelope);

    assertThat(receivedKeys).containsExactly("my:key");
    assertThat(receivedPayloads).containsExactly("my-payload");
  }

  @Test
  void parseAndDispatchIgnoresMessagesWithoutDelimiter() {
    List<String> receivedKeys = new ArrayList<>();

    notifier.subscribe((key, payload) -> receivedKeys.add(key));

    notifier.parseAndDispatch("no-delimiter");

    assertThat(receivedKeys).isEmpty();
  }

  @Test
  void extractSnsMessageHandlesEscapedCharacters() {
    String envelope =
        """
        {"Message": "key\\|with\\\\pipe|payload"}
        """;

    String result = notifier.extractSnsMessage(envelope);
    assertThat(result).isEqualTo("key|with\\pipe|payload");
  }

  @Test
  void extractSnsMessageReturnsRawBodyWhenNotEnvelope() {
    String raw = "key|payload";
    assertThat(notifier.extractSnsMessage(raw)).isEqualTo(raw);
  }

  @Test
  void stopWhenNotRunningIsNoOp() {
    notifier.stop();
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void multipleHandlersAreRegistered() {
    List<String> handler1 = new ArrayList<>();
    List<String> handler2 = new ArrayList<>();

    notifier.subscribe((key, payload) -> handler1.add(payload));
    notifier.subscribe((key, payload) -> handler2.add(payload));

    notifier.parseAndDispatch("key|value");

    assertThat(handler1).containsExactly("value");
    assertThat(handler2).containsExactly("value");
  }
}
