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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.substrate.core.notifier.NotificationHandler;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.notifier.NotifierSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

public class SnsNotifier implements NotifierSpi, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SnsNotifier.class);
  private static final String DELIMITER = "|";
  private static final int SQS_MAX_MESSAGES_PER_POLL = 10;

  private final SnsClient snsClient;
  private final SqsClient sqsClient;
  private final String topicArn;
  private final int sqsMessageRetentionSeconds;
  private final int sqsWaitTimeSeconds;
  private final List<NotificationHandler> handlers = new CopyOnWriteArrayList<>();

  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile String queueUrl;
  private volatile String subscriptionArn;
  private final AtomicReference<Thread> pollerThread = new AtomicReference<>();

  public SnsNotifier(
      SnsClient snsClient,
      SqsClient sqsClient,
      String topicArn,
      int sqsMessageRetentionSeconds,
      int sqsWaitTimeSeconds) {
    this.snsClient = snsClient;
    this.sqsClient = sqsClient;
    this.topicArn = topicArn;
    this.sqsMessageRetentionSeconds = sqsMessageRetentionSeconds;
    this.sqsWaitTimeSeconds = sqsWaitTimeSeconds;
  }

  @Override
  public void notify(String key, String payload) {
    snsClient.publish(
        PublishRequest.builder().topicArn(topicArn).message(key + DELIMITER + payload).build());
  }

  @Override
  public NotifierSubscription subscribe(NotificationHandler handler) {
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  @Override
  public void start() {
    String queueName = "substrate-" + UUID.randomUUID();
    queueUrl =
        sqsClient
            .createQueue(
                CreateQueueRequest.builder()
                    .queueName(queueName)
                    .attributes(
                        Map.of(
                            QueueAttributeName.MESSAGE_RETENTION_PERIOD,
                            String.valueOf(sqsMessageRetentionSeconds)))
                    .build())
            .queueUrl();

    String queueArn =
        sqsClient
            .getQueueAttributes(
                GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build())
            .attributes()
            .get(QueueAttributeName.QUEUE_ARN);

    // Allow SNS to send messages to this SQS queue
    String policy =
        """
        {
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Principal": {"Service": "sns.amazonaws.com"},
              "Action": "sqs:SendMessage",
              "Resource": "%s",
              "Condition": {
                "ArnEquals": {
                  "aws:SourceArn": "%s"
                }
              }
            }
          ]
        }
        """
            .formatted(queueArn, topicArn);

    sqsClient.setQueueAttributes(
        SetQueueAttributesRequest.builder()
            .queueUrl(queueUrl)
            .attributes(Map.of(QueueAttributeName.POLICY, policy))
            .build());

    SubscribeResponse subscribeResponse =
        snsClient.subscribe(
            SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(queueArn)
                .build());
    subscriptionArn = subscribeResponse.subscriptionArn();

    running.set(true);
    pollerThread.set(Thread.ofVirtual().name("substrate-sns-poller").start(this::pollLoop));
  }

  @Override
  public void stop() {
    running.set(false);
    Thread thread = pollerThread.getAndSet(null);
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(5000);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
    if (subscriptionArn != null) {
      try {
        snsClient.unsubscribe(
            UnsubscribeRequest.builder().subscriptionArn(subscriptionArn).build());
      } catch (Exception e) {
        log.warn("Failed to unsubscribe from SNS topic: {}", e.getMessage());
      }
      subscriptionArn = null;
    }
    if (queueUrl != null) {
      try {
        sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
      } catch (Exception e) {
        log.warn("Failed to delete temporary SQS queue: {}", e.getMessage());
      }
      queueUrl = null;
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void pollLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        ReceiveMessageResponse response =
            sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .waitTimeSeconds(sqsWaitTimeSeconds)
                    .maxNumberOfMessages(SQS_MAX_MESSAGES_PER_POLL)
                    .build());

        List<Message> messages = response.messages();
        if (!messages.isEmpty()) {
          List<DeleteMessageBatchRequestEntry> deleteEntries = new ArrayList<>(messages.size());

          for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            parseAndDispatch(message.body());
            deleteEntries.add(
                DeleteMessageBatchRequestEntry.builder()
                    .id(String.valueOf(i))
                    .receiptHandle(message.receiptHandle())
                    .build());
          }

          sqsClient.deleteMessageBatch(
              DeleteMessageBatchRequest.builder()
                  .queueUrl(queueUrl)
                  .entries(deleteEntries)
                  .build());
        }
      } catch (Exception e) {
        if (Thread.currentThread().isInterrupted() || !running.get()) {
          break;
        }
        log.warn("Error polling SQS queue: {}", e.getMessage());
      }
    }
  }

  void parseAndDispatch(String body) {
    String payload = extractSnsMessage(body);
    int delimiterIndex = payload.indexOf(DELIMITER);
    if (delimiterIndex < 0) {
      return;
    }
    String key = payload.substring(0, delimiterIndex);
    String value = payload.substring(delimiterIndex + 1);
    for (NotificationHandler handler : handlers) {
      handler.onNotification(key, value);
    }
  }

  String extractSnsMessage(String body) {
    // SNS wraps the original message in a JSON envelope when delivering to SQS.
    // Extract the "Message" field value using simple string parsing to avoid a JSON dependency.
    String marker = "\"Message\"";
    int keyIndex = body.indexOf(marker);
    if (keyIndex < 0) {
      return body;
    }
    int colonIndex = body.indexOf(':', keyIndex + marker.length());
    if (colonIndex < 0) {
      return body;
    }
    int openQuote = body.indexOf('"', colonIndex + 1);
    if (openQuote < 0) {
      return body;
    }
    int closeQuote = findClosingQuote(body, openQuote + 1);
    if (closeQuote < 0) {
      return body;
    }
    return unescape(body.substring(openQuote + 1, closeQuote));
  }

  private int findClosingQuote(String s, int from) {
    int i = from;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == '"') {
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  private String unescape(String s) {
    if (s.indexOf('\\') < 0) {
      return s;
    }
    StringBuilder sb = new StringBuilder(s.length());
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        i++;
        sb.append(s.charAt(i));
      } else {
        sb.append(c);
      }
      i++;
    }
    return sb.toString();
  }
}
