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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;

@ExtendWith(MockitoExtension.class)
class SnsNotifierSpiTest {

  @Mock private SnsClient snsClient;
  @Mock private SqsClient sqsClient;

  private SnsNotifierSpi notifier;

  @BeforeEach
  void setUp() {
    notifier =
        new SnsNotifierSpi(snsClient, sqsClient, "arn:aws:sns:us-east-1:123456789:test", 60, 20);
  }

  @Test
  void notifyPublishesBase64EncodedPayload() {
    byte[] payload = "my-payload".getBytes(UTF_8);

    notifier.notify(payload);

    ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
    verify(snsClient).publish(captor.capture());
    PublishRequest request = captor.getValue();
    assertThat(request.topicArn()).isEqualTo("arn:aws:sns:us-east-1:123456789:test");
    assertThat(Base64.getDecoder().decode(request.message())).isEqualTo(payload);
  }

  @Test
  void parseAndDispatchDecodesBase64Payload() {
    List<byte[]> received = new ArrayList<>();
    notifier.subscribe(received::add);

    byte[] original = "test-payload".getBytes(UTF_8);
    String encoded = Base64.getEncoder().encodeToString(original);
    notifier.parseAndDispatch(encoded);

    assertThat(received).hasSize(1);
    assertThat(received.get(0)).isEqualTo(original);
  }

  @Test
  void parseAndDispatchHandlesSnsEnvelope() {
    List<byte[]> received = new ArrayList<>();
    notifier.subscribe(received::add);

    byte[] original = "my-payload".getBytes(UTF_8);
    String encoded = Base64.getEncoder().encodeToString(original);

    String snsEnvelope =
        """
        {
          "Type": "Notification",
          "MessageId": "abc-123",
          "TopicArn": "arn:aws:sns:us-east-1:123456789:test",
          "Message": "%s",
          "Timestamp": "2026-01-01T00:00:00.000Z"
        }
        """
            .formatted(encoded);

    notifier.parseAndDispatch(snsEnvelope);

    assertThat(received).hasSize(1);
    assertThat(received.get(0)).isEqualTo(original);
  }

  @Test
  void parseAndDispatchIgnoresInvalidBase64() {
    List<byte[]> received = new ArrayList<>();
    notifier.subscribe(received::add);

    notifier.parseAndDispatch("not-valid-base64!!!");

    assertThat(received).isEmpty();
  }

  @Test
  void extractSnsMessageHandlesEscapedCharacters() {
    String encoded = Base64.getEncoder().encodeToString("test".getBytes(UTF_8));
    String envelope = "{\"Message\": \"" + encoded + "\"}";

    String result = notifier.extractSnsMessage(envelope);
    assertThat(result).isEqualTo(encoded);
  }

  @Test
  void extractSnsMessageReturnsRawBodyWhenNotEnvelope() {
    String raw = Base64.getEncoder().encodeToString("payload".getBytes(UTF_8));
    assertThat(notifier.extractSnsMessage(raw)).isEqualTo(raw);
  }

  @Test
  void stopWhenNotRunningIsNoOp() {
    notifier.stop();
    assertThat(notifier.isRunning()).isFalse();
  }

  @Test
  void multipleHandlersAreRegistered() {
    List<byte[]> handler1 = new ArrayList<>();
    List<byte[]> handler2 = new ArrayList<>();

    notifier.subscribe(handler1::add);
    notifier.subscribe(handler2::add);

    byte[] original = "value".getBytes(UTF_8);
    String encoded = Base64.getEncoder().encodeToString(original);
    notifier.parseAndDispatch(encoded);

    assertThat(handler1).hasSize(1);
    assertThat(handler1.get(0)).isEqualTo(original);
    assertThat(handler2).hasSize(1);
    assertThat(handler2.get(0)).isEqualTo(original);
  }

  @Test
  void subscribeCancelRemovesHandler() {
    List<byte[]> received = new ArrayList<>();

    var subscription = notifier.subscribe(received::add);

    byte[] first = "first".getBytes(UTF_8);
    notifier.parseAndDispatch(Base64.getEncoder().encodeToString(first));
    assertThat(received).hasSize(1);

    subscription.cancel();

    byte[] second = "second".getBytes(UTF_8);
    notifier.parseAndDispatch(Base64.getEncoder().encodeToString(second));
    assertThat(received).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"Type\": \"Notification\", \"TopicArn\": \"arn:aws:sns:us-east-1:123:test\"}",
        "{\"Message\" no-colon-here \"value\"}",
        "{\"Message\": \"unterminated value}"
      })
  void extractSnsMessageReturnsFallbackForMalformedJson(String body) {
    assertThat(notifier.extractSnsMessage(body)).isEqualTo(body);
  }

  @Test
  void unescapeWithTrailingBackslash() {
    String body = "{\"Message\": \"trailing\\\\\"}";
    String result = notifier.extractSnsMessage(body);
    assertThat(result).isEqualTo("trailing\\");
  }

  @Test
  void unescapeWithTrailingLoneBackslashInValue() {
    String body = "{\"Message\": \"abc\\\\\"}";
    String result = notifier.extractSnsMessage(body);
    assertThat(result).isEqualTo("abc\\");
  }
}
