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
package org.jwcarman.substrate.rabbitmq.journal;

import com.rabbitmq.stream.ByteCapacity;
import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.OffsetSpecification;
import com.rabbitmq.stream.Producer;
import com.rabbitmq.stream.StreamException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.substrate.core.journal.AbstractJournalSpi;
import org.jwcarman.substrate.core.journal.RawJournalEntry;

public class RabbitMqJournalSpi extends AbstractJournalSpi implements AutoCloseable {

  private static final long CONSUME_TIMEOUT_MS = 200;
  private static final long PUBLISH_TIMEOUT_SECONDS = 5;
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String FIELD_ENTRY_ID = "entryId";
  private static final String FIELD_JOURNAL_KEY = "journalKey";
  private static final String COMPLETED_MARKER = "__COMPLETED__";

  private final Environment environment;
  private final Duration maxAge;
  private final long maxLengthBytes;
  private final ConcurrentHashMap<String, Producer> producers = new ConcurrentHashMap<>();

  public RabbitMqJournalSpi(
      Environment environment, String prefix, Duration maxAge, long maxLengthBytes) {
    super(prefix);
    this.environment = environment;
    this.maxAge = maxAge;
    this.maxLengthBytes = maxLengthBytes;
  }

  @Override
  public String append(String key, byte[] data, Duration ttl) {
    String streamName = toStreamName(key);
    ensureStreamExists(streamName);
    String entryId = generateEntryId();
    Producer producer = getOrCreateProducer(streamName);

    Message message = buildMessage(producer, entryId, key, data);
    publishWithConfirmation(producer, message);

    return entryId;
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    return consumeAll(key).stream().filter(entry -> entry.id().compareTo(afterId) > 0).toList();
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    List<RawJournalEntry> all = consumeAll(key);
    int start = Math.max(0, all.size() - count);
    return List.copyOf(all.subList(start, all.size()));
  }

  @Override
  public void complete(String key, Duration retentionTtl) {
    String streamName = toStreamName(key);
    ensureStreamExists(streamName);
    Producer producer = getOrCreateProducer(streamName);

    Message message =
        producer
            .messageBuilder()
            .applicationProperties()
            .entry(FIELD_ENTRY_ID, COMPLETED_MARKER)
            .entry(FIELD_JOURNAL_KEY, key)
            .entry(FIELD_TIMESTAMP, Instant.now().toString())
            .messageBuilder()
            .addData(COMPLETED_MARKER.getBytes(StandardCharsets.UTF_8))
            .build();

    publishWithConfirmation(producer, message);
  }

  @Override
  public boolean isComplete(String key) {
    // consumeAll filters out COMPLETED markers, so check the raw stream
    String streamName = toStreamName(key);
    if (!streamExists(streamName)) {
      return false;
    }
    BlockingQueue<Boolean> result = new LinkedBlockingQueue<>();
    Consumer consumer;
    try {
      consumer =
          environment.consumerBuilder().stream(streamName)
              .offset(OffsetSpecification.first())
              .messageHandler(
                  (ctx, msg) -> {
                    var props = msg.getApplicationProperties();
                    if (props != null && COMPLETED_MARKER.equals(props.get(FIELD_ENTRY_ID))) {
                      result.add(Boolean.TRUE);
                    }
                  })
              .build();
    } catch (StreamException _) {
      return false;
    }
    try {
      Boolean found = result.poll(CONSUME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      return found != null && found;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      consumer.close();
    }
  }

  @Override
  public void delete(String key) {
    String streamName = toStreamName(key);
    Producer producer = producers.remove(streamName);
    if (producer != null) {
      producer.close();
    }
    try {
      environment.deleteStream(streamName);
    } catch (StreamException _) {
      // Stream doesn't exist — safe to ignore
    }
  }

  @Override
  public void close() {
    producers.values().forEach(Producer::close);
    producers.clear();
  }

  private void ensureStreamExists(String streamName) {
    try {
      environment.streamCreator().stream(streamName)
          .maxAge(maxAge)
          .maxLengthBytes(ByteCapacity.B(maxLengthBytes))
          .create();
    } catch (StreamException _) {
      // Stream already exists — safe to ignore
    }
  }

  private Producer getOrCreateProducer(String streamName) {
    return producers.computeIfAbsent(
        streamName, name -> environment.producerBuilder().stream(name).build());
  }

  private Message buildMessage(Producer producer, String entryId, String key, byte[] data) {
    return producer
        .messageBuilder()
        .applicationProperties()
        .entry(FIELD_ENTRY_ID, entryId)
        .entry(FIELD_JOURNAL_KEY, key)
        .entry(FIELD_TIMESTAMP, Instant.now().toString())
        .messageBuilder()
        .addData(data)
        .build();
  }

  private void publishWithConfirmation(Producer producer, Message message) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> error = new AtomicReference<>();
    producer.send(
        message,
        status -> {
          if (!status.isConfirmed()) {
            error.set(new StreamException("Message not confirmed: " + status.getCode()));
          }
          latch.countDown();
        });

    try {
      if (!latch.await(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new StreamException("Publish confirmation timed out");
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new StreamException("Interrupted while waiting for publish confirmation");
    }

    if (error.get() != null) {
      throw new StreamException("Failed to publish message", error.get());
    }
  }

  private List<RawJournalEntry> consumeAll(String key) {
    String streamName = toStreamName(key);
    if (!streamExists(streamName)) {
      return List.of();
    }

    BlockingQueue<RawJournalEntry> queue = new LinkedBlockingQueue<>();
    Consumer consumer;
    try {
      consumer =
          environment.consumerBuilder().stream(streamName)
              .offset(OffsetSpecification.first())
              .messageHandler((ctx, msg) -> deserializeEntry(msg, key, queue))
              .build();
    } catch (StreamException _) {
      return List.of();
    }

    List<RawJournalEntry> entries = new ArrayList<>();
    try {
      RawJournalEntry entry;
      while ((entry = queue.poll(CONSUME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) != null) {
        entries.add(entry);
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } finally {
      consumer.close();
    }

    return Collections.unmodifiableList(entries);
  }

  private void deserializeEntry(Message message, String key, BlockingQueue<RawJournalEntry> queue) {
    var props = message.getApplicationProperties();
    if (props == null) {
      return;
    }

    String entryId = (String) props.get(FIELD_ENTRY_ID);
    if (COMPLETED_MARKER.equals(entryId)) {
      return;
    }

    String timestampStr = (String) props.get(FIELD_TIMESTAMP);
    Instant timestamp = timestampStr != null ? Instant.parse(timestampStr) : Instant.now();

    byte[] data = message.getBodyAsBinary() != null ? message.getBodyAsBinary() : new byte[0];

    queue.add(new RawJournalEntry(entryId, key, data, timestamp));
  }

  private boolean streamExists(String streamName) {
    try {
      environment.queryStreamStats(streamName);
      return true;
    } catch (StreamException _) {
      return false;
    }
  }

  private String toStreamName(String key) {
    return key.replace(':', '-');
  }
}
