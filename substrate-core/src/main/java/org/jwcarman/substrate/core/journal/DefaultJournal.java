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
package org.jwcarman.substrate.core.journal;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.substrate.BlockingSubscription;
import org.jwcarman.substrate.CallbackSubscriberBuilder;
import org.jwcarman.substrate.CallbackSubscription;
import org.jwcarman.substrate.core.notifier.NotifierSpi;
import org.jwcarman.substrate.core.subscription.BlockingBoundedHandoff;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscriberBuilder;
import org.jwcarman.substrate.core.subscription.DefaultCallbackSubscription;
import org.jwcarman.substrate.core.subscription.FeederSupport;
import org.jwcarman.substrate.journal.Journal;
import org.jwcarman.substrate.journal.JournalEntry;
import org.jwcarman.substrate.journal.JournalExpiredException;

public class DefaultJournal<T> implements Journal<T> {

  private final JournalSpi journalSpi;
  private final String key;
  private final Codec<T> codec;
  private final NotifierSpi notifier;
  private final int subscriptionQueueCapacity;
  private final Duration maxEntryTtl;
  private final Duration maxRetentionTtl;

  public DefaultJournal(
      JournalSpi journalSpi,
      String key,
      Codec<T> codec,
      NotifierSpi notifier,
      int subscriptionQueueCapacity,
      Duration maxEntryTtl,
      Duration maxRetentionTtl) {
    this.journalSpi = journalSpi;
    this.key = key;
    this.codec = codec;
    this.notifier = notifier;
    this.subscriptionQueueCapacity = subscriptionQueueCapacity;
    this.maxEntryTtl = maxEntryTtl;
    this.maxRetentionTtl = maxRetentionTtl;
  }

  @Override
  public String append(T data, Duration ttl) {
    if (ttl.compareTo(maxEntryTtl) > 0) {
      throw new IllegalArgumentException(
          "Journal entry TTL " + ttl + " exceeds configured maximum " + maxEntryTtl);
    }
    byte[] bytes = codec.encode(data);
    String entryId = journalSpi.append(key, bytes, ttl);
    notifier.notify(key, entryId);
    return entryId;
  }

  @Override
  public void complete(Duration retentionTtl) {
    if (retentionTtl.compareTo(maxRetentionTtl) > 0) {
      throw new IllegalArgumentException(
          "Journal retention TTL "
              + retentionTtl
              + " exceeds configured maximum "
              + maxRetentionTtl);
    }
    journalSpi.complete(key, retentionTtl);
    notifier.notify(key, "__COMPLETED__");
  }

  @Override
  public void delete() {
    journalSpi.delete(key);
    notifier.notify(key, "__DELETED__");
  }

  @Override
  public BlockingSubscription<JournalEntry<T>> subscribe() {
    List<RawJournalEntry> lastEntries = journalSpi.readLast(key, 1);
    String startingCheckpoint = lastEntries.isEmpty() ? null : lastEntries.getLast().id();
    return buildBlockingSubscription(startingCheckpoint, List.of());
  }

  @Override
  public BlockingSubscription<JournalEntry<T>> subscribeAfter(String afterId) {
    return buildBlockingSubscription(afterId, List.of());
  }

  @Override
  public BlockingSubscription<JournalEntry<T>> subscribeLast(int count) {
    List<RawJournalEntry> preload = journalSpi.readLast(key, count);
    String startingCheckpoint = preload.isEmpty() ? null : preload.getLast().id();
    return buildBlockingSubscription(startingCheckpoint, preload);
  }

  @Override
  public CallbackSubscription subscribe(Consumer<JournalEntry<T>> onNext) {
    List<RawJournalEntry> lastEntries = journalSpi.readLast(key, 1);
    String startingCheckpoint = lastEntries.isEmpty() ? null : lastEntries.getLast().id();
    return buildCallbackSubscription(startingCheckpoint, List.of(), onNext, null);
  }

  @Override
  public CallbackSubscription subscribe(
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer) {
    List<RawJournalEntry> lastEntries = journalSpi.readLast(key, 1);
    String startingCheckpoint = lastEntries.isEmpty() ? null : lastEntries.getLast().id();
    return buildCallbackSubscription(startingCheckpoint, List.of(), onNext, customizer);
  }

  @Override
  public CallbackSubscription subscribeAfter(String afterId, Consumer<JournalEntry<T>> onNext) {
    return buildCallbackSubscription(afterId, List.of(), onNext, null);
  }

  @Override
  public CallbackSubscription subscribeAfter(
      String afterId,
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer) {
    return buildCallbackSubscription(afterId, List.of(), onNext, customizer);
  }

  @Override
  public CallbackSubscription subscribeLast(int count, Consumer<JournalEntry<T>> onNext) {
    List<RawJournalEntry> preload = journalSpi.readLast(key, count);
    String startingCheckpoint = preload.isEmpty() ? null : preload.getLast().id();
    return buildCallbackSubscription(startingCheckpoint, preload, onNext, null);
  }

  @Override
  public CallbackSubscription subscribeLast(
      int count,
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer) {
    List<RawJournalEntry> preload = journalSpi.readLast(key, count);
    String startingCheckpoint = preload.isEmpty() ? null : preload.getLast().id();
    return buildCallbackSubscription(startingCheckpoint, preload, onNext, customizer);
  }

  @Override
  public String key() {
    return key;
  }

  private BlockingSubscription<JournalEntry<T>> buildBlockingSubscription(
      String startingCheckpoint, List<RawJournalEntry> preload) {
    BlockingBoundedHandoff<JournalEntry<T>> handoff =
        new BlockingBoundedHandoff<>(subscriptionQueueCapacity);
    Runnable canceller = startFeeder(handoff, startingCheckpoint, preload);
    return new DefaultBlockingSubscription<>(handoff, canceller);
  }

  private CallbackSubscription buildCallbackSubscription(
      String startingCheckpoint,
      List<RawJournalEntry> preload,
      Consumer<JournalEntry<T>> onNext,
      Consumer<CallbackSubscriberBuilder<JournalEntry<T>>> customizer) {
    BlockingBoundedHandoff<JournalEntry<T>> handoff =
        new BlockingBoundedHandoff<>(subscriptionQueueCapacity);
    Runnable canceller = startFeeder(handoff, startingCheckpoint, preload);

    DefaultCallbackSubscriberBuilder<JournalEntry<T>> builder =
        new DefaultCallbackSubscriberBuilder<>();
    if (customizer != null) {
      customizer.accept(builder);
    }

    return new DefaultCallbackSubscription<>(
        handoff,
        canceller,
        onNext,
        builder.errorHandler(),
        builder.expirationHandler(),
        builder.deleteHandler(),
        builder.completeHandler());
  }

  private Runnable startFeeder(
      BlockingBoundedHandoff<JournalEntry<T>> handoff,
      String startingCheckpoint,
      List<RawJournalEntry> preload) {
    AtomicReference<String> checkpoint = new AtomicReference<>(startingCheckpoint);
    AtomicBoolean preloaded = new AtomicBoolean(false);

    return FeederSupport.start(
        key,
        notifier,
        handoff,
        "substrate-journal-feeder",
        () -> runOneIteration(handoff, checkpoint, preloaded, preload));
  }

  private boolean runOneIteration(
      BlockingBoundedHandoff<JournalEntry<T>> handoff,
      AtomicReference<String> checkpoint,
      AtomicBoolean preloaded,
      List<RawJournalEntry> preload) {
    pushPreloadIfNeeded(handoff, checkpoint, preloaded, preload);
    if (!readBatchAndPush(handoff, checkpoint)) {
      return false;
    }
    return !drainIfCompleted(handoff, checkpoint);
  }

  private void pushPreloadIfNeeded(
      BlockingBoundedHandoff<JournalEntry<T>> handoff,
      AtomicReference<String> checkpoint,
      AtomicBoolean preloaded,
      List<RawJournalEntry> preload) {
    if (!preloaded.compareAndSet(false, true)) {
      return;
    }
    for (RawJournalEntry raw : preload) {
      handoff.push(decode(raw));
      checkpoint.set(raw.id());
    }
  }

  private boolean readBatchAndPush(
      BlockingBoundedHandoff<JournalEntry<T>> handoff, AtomicReference<String> checkpoint) {
    try {
      List<RawJournalEntry> batch = readAfterCheckpoint(checkpoint.get());
      for (RawJournalEntry raw : batch) {
        handoff.push(decode(raw));
        checkpoint.set(raw.id());
      }
      return true;
    } catch (JournalExpiredException _) {
      handoff.markExpired();
      return false;
    }
  }

  private boolean drainIfCompleted(
      BlockingBoundedHandoff<JournalEntry<T>> handoff, AtomicReference<String> checkpoint) {
    if (!journalSpi.isComplete(key)) {
      return false;
    }
    try {
      List<RawJournalEntry> finalBatch = readAfterCheckpoint(checkpoint.get());
      for (RawJournalEntry raw : finalBatch) {
        handoff.push(decode(raw));
        checkpoint.set(raw.id());
      }
    } catch (JournalExpiredException _) {
      handoff.markExpired();
      return true;
    }
    handoff.markCompleted();
    return true;
  }

  private List<RawJournalEntry> readAfterCheckpoint(String cp) {
    if (cp != null) {
      return journalSpi.readAfter(key, cp);
    }
    return journalSpi.readLast(key, Integer.MAX_VALUE);
  }

  private JournalEntry<T> decode(RawJournalEntry raw) {
    return new JournalEntry<>(raw.id(), raw.key(), codec.decode(raw.data()), raw.timestamp());
  }
}
