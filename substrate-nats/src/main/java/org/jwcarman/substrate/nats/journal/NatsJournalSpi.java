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
package org.jwcarman.substrate.nats.journal;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.PurgeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.StreamInfoOptions;
import io.nats.client.api.Subject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.substrate.core.journal.AbstractJournalSpi;
import org.jwcarman.substrate.core.journal.RawJournalEntry;

public class NatsJournalSpi extends AbstractJournalSpi {

  private static final String SUBJECT_PREFIX = "substrate.journal.";
  private static final String COMPLETED_BUCKET = "substrate-journal-completed";

  private final JetStream jetStream;
  private final JetStreamManagement jsm;
  private final Connection connection;
  private final String streamName;
  private final Duration fetchTimeout;
  private final int tailBatchSize;

  public NatsJournalSpi(
      Connection connection,
      String prefix,
      String streamName,
      Duration maxAge,
      long maxMessages,
      Duration fetchTimeout,
      int tailBatchSize) {
    super(prefix);
    this.connection = connection;
    this.streamName = streamName;
    this.fetchTimeout = fetchTimeout;
    this.tailBatchSize = tailBatchSize;
    try {
      this.jetStream = connection.jetStream();
      this.jsm = connection.jetStreamManagement();
      ensureStreamExists(maxAge, maxMessages);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to initialize NATS JetStream", e);
    }
  }

  @Override
  public String append(String key, byte[] data, Duration ttl) {
    try {
      String subject = toSubject(key);

      io.nats.client.impl.NatsMessage message =
          io.nats.client.impl.NatsMessage.builder()
              .subject(subject)
              .headers(
                  new io.nats.client.impl.Headers()
                      .add("timestamp", Instant.now().toString())
                      .add("journalKey", key))
              .data(data)
              .build();

      var ack = jetStream.publish(message);
      return String.valueOf(ack.getSeqno());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to publish to NATS JetStream", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to publish to NATS JetStream", e);
    }
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    long startSeq = Long.parseLong(afterId) + 1;
    String subject = toSubject(key);

    try {
      StreamInfo info = jsm.getStreamInfo(streamName);
      long lastSeq = info.getStreamState().getLastSequence();
      if (startSeq > lastSeq) {
        return List.of();
      }
      return fetchMessages(subject, DeliverPolicy.ByStartSequence, startSeq, key);
    } catch (IOException | JetStreamApiException _) {
      return List.of();
    }
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    String subject = toSubject(key);

    try {
      long subjectMessageCount = getSubjectMessageCount(subject);
      if (subjectMessageCount == 0) {
        return List.of();
      }

      List<RawJournalEntry> allEntries = fetchAllForSubject(subject, key);
      int start = Math.max(0, allEntries.size() - count);
      return List.copyOf(allEntries.subList(start, allEntries.size()));
    } catch (IOException | JetStreamApiException _) {
      return List.of();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return List.of();
    }
  }

  @Override
  public void complete(String key, Duration retentionTtl) {
    try {
      ensureCompletedBucketExists();
      var kv = connection.keyValue(COMPLETED_BUCKET);
      kv.put(key.replace(':', '.'), "true".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to mark journal as complete", e);
    } catch (JetStreamApiException e) {
      throw new IllegalStateException("Failed to mark journal as complete", e);
    }
  }

  private void ensureCompletedBucketExists() throws IOException, JetStreamApiException {
    var kvm = connection.keyValueManagement();
    try {
      kvm.getStatus(COMPLETED_BUCKET);
    } catch (JetStreamApiException _) {
      kvm.create(
          KeyValueConfiguration.builder().name(COMPLETED_BUCKET).maxHistoryPerKey(1).build());
    }
  }

  @Override
  public boolean isComplete(String key) {
    try {
      ensureCompletedBucketExists();
      var kv = connection.keyValue(COMPLETED_BUCKET);
      var entry = kv.get(key.replace(':', '.'));
      return entry != null;
    } catch (IOException | JetStreamApiException _) {
      return false;
    }
  }

  @Override
  public boolean exists(String key) {
    try {
      return getSubjectMessageCount(toSubject(key)) > 0;
    } catch (IOException | JetStreamApiException _) {
      return false;
    }
  }

  @Override
  public void delete(String key) {
    String subject = toSubject(key);
    try {
      jsm.purgeStream(streamName, PurgeOptions.subject(subject));
    } catch (IOException | JetStreamApiException _) {
      // Stream or subject doesn't exist — safe to ignore
    }
  }

  private void ensureStreamExists(Duration maxAge, long maxMessages) {
    StreamConfiguration config =
        StreamConfiguration.builder()
            .name(streamName)
            .subjects(SUBJECT_PREFIX + ">")
            .retentionPolicy(RetentionPolicy.Limits)
            .storageType(StorageType.File)
            .maxAge(maxAge)
            .maxMessages(maxMessages)
            .build();
    try {
      jsm.addStream(config);
    } catch (JetStreamApiException e) {
      if (e.getApiErrorCode() == 10058) {
        try {
          jsm.updateStream(config);
        } catch (IOException ex) {
          throw new UncheckedIOException("Failed to update NATS JetStream stream", ex);
        } catch (JetStreamApiException ex) {
          throw new IllegalStateException("Failed to update NATS JetStream stream", ex);
        }
      } else {
        throw new IllegalStateException("Failed to create NATS JetStream stream", e);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create NATS JetStream stream", e);
    }
  }

  private String toSubject(String key) {
    return SUBJECT_PREFIX + key.replace(':', '.');
  }

  private List<RawJournalEntry> fetchMessages(
      String subject, DeliverPolicy deliverPolicy, long startSeq, String key) {
    try {
      ConsumerConfiguration.Builder ccBuilder =
          ConsumerConfiguration.builder().filterSubject(subject).deliverPolicy(deliverPolicy);
      if (deliverPolicy == DeliverPolicy.ByStartSequence) {
        ccBuilder.startSequence(startSeq);
      }

      PullSubscribeOptions pullOptions =
          PullSubscribeOptions.builder().stream(streamName)
              .configuration(ccBuilder.build())
              .build();

      JetStreamSubscription sub = jetStream.subscribe(subject, pullOptions);

      List<RawJournalEntry> entries = new ArrayList<>();
      try {
        drainAvailable(sub, key, entries, tailBatchSize);
      } finally {
        sub.unsubscribe();
      }
      return entries;
    } catch (IOException | JetStreamApiException _) {
      return List.of();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return List.of();
    }
  }

  private List<RawJournalEntry> fetchAllForSubject(String subject, String key)
      throws IOException, JetStreamApiException, InterruptedException {
    PullSubscribeOptions pullOptions =
        PullSubscribeOptions.builder().stream(streamName)
            .configuration(
                ConsumerConfiguration.builder()
                    .filterSubject(subject)
                    .deliverPolicy(DeliverPolicy.All)
                    .build())
            .build();

    JetStreamSubscription sub = jetStream.subscribe(subject, pullOptions);

    List<RawJournalEntry> entries = new ArrayList<>();
    try {
      drainAvailable(sub, key, entries, 1000);
    } finally {
      sub.unsubscribe();
    }
    return entries;
  }

  private void drainAvailable(
      JetStreamSubscription sub, String key, List<RawJournalEntry> entries, int batchSize)
      throws InterruptedException {
    int effectiveBatch = Math.max(batchSize, 1);
    while (true) {
      sub.pullNoWait(effectiveBatch);
      int collected = 0;
      while (true) {
        Message msg = sub.nextMessage(fetchTimeout);
        if (msg == null || msg.isStatusMessage()) {
          break;
        }
        entries.add(toJournalEntry(msg, key));
        collected++;
      }
      if (collected < effectiveBatch) {
        return;
      }
    }
  }

  private long getSubjectMessageCount(String subject) throws IOException, JetStreamApiException {
    StreamInfo info = jsm.getStreamInfo(streamName, StreamInfoOptions.filterSubjects(subject));
    List<Subject> subjects = info.getStreamState().getSubjects();
    if (subjects.isEmpty()) {
      return 0;
    }
    return subjects.getFirst().getCount();
  }

  private RawJournalEntry toJournalEntry(Message message, String key) {
    byte[] data = message.getData() != null ? message.getData() : new byte[0];

    io.nats.client.impl.Headers headers = message.getHeaders();
    String timestampStr = headers != null ? getSingleHeader(headers, "timestamp") : null;
    Instant timestamp = timestampStr != null ? Instant.parse(timestampStr) : Instant.now();

    return new RawJournalEntry(
        String.valueOf(message.metaData().streamSequence()), key, data, timestamp);
  }

  private String getSingleHeader(io.nats.client.impl.Headers headers, String headerKey) {
    List<String> values = headers.get(headerKey);
    return values != null && !values.isEmpty() ? values.getFirst() : null;
  }
}
