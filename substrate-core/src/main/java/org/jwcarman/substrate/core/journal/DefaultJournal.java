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
import org.jwcarman.substrate.Subscriber;
import org.jwcarman.substrate.SubscriberConfig;
import org.jwcarman.substrate.Subscription;
import org.jwcarman.substrate.core.subscription.BoundedQueueHandoff;
import org.jwcarman.substrate.core.subscription.CallbackPumpSubscription;
import org.jwcarman.substrate.core.subscription.DefaultBlockingSubscription;
import org.jwcarman.substrate.core.subscription.DefaultSubscriberBuilder;
import org.jwcarman.substrate.core.subscription.FeederSupport;
import org.jwcarman.substrate.journal.Journal;
import org.jwcarman.substrate.journal.JournalEntry;
import org.jwcarman.substrate.journal.JournalExpiredException;
import org.jwcarman.substrate.journal.JournalNotFoundException;

public class DefaultJournal<T> implements Journal<T> {

  private final JournalContext context;
  private final String key;
  private final Codec<T> codec;
  private final AtomicBoolean connected;

  public DefaultJournal(JournalContext context, String key, Codec<T> codec, boolean connected) {
    this.context = context;
    this.key = key;
    this.codec = codec;
    this.connected = new AtomicBoolean(connected);
  }

  private void ensureExists() {
    if (connected.compareAndSet(true, false) && !context.spi().exists(key)) {
      throw new JournalNotFoundException(key);
    }
  }

  @Override
  public String append(T data, Duration ttl) {
    ensureExists();
    if (ttl.compareTo(context.limits().maxEntryTtl()) > 0) {
      throw new IllegalArgumentException(
          "Journal entry TTL "
              + ttl
              + " exceeds configured maximum "
              + context.limits().maxEntryTtl());
    }
    byte[] bytes = codec.encode(data);
    String entryId = context.spi().append(key, context.transformer().encode(bytes), ttl);
    context.notifier().notifyJournalChanged(key);
    return entryId;
  }

  @Override
  public void complete(Duration retentionTtl) {
    ensureExists();
    if (retentionTtl.compareTo(context.limits().maxRetentionTtl()) > 0) {
      throw new IllegalArgumentException(
          "Journal retention TTL "
              + retentionTtl
              + " exceeds configured maximum "
              + context.limits().maxRetentionTtl());
    }
    context.spi().complete(key, retentionTtl);
    context.notifier().notifyJournalCompleted(key);
  }

  @Override
  public void delete() {
    // delete() is idempotent — no existence probe, even for connected handles
    context.spi().delete(key);
    context.notifier().notifyJournalDeleted(key);
  }

  @Override
  public BlockingSubscription<JournalEntry<T>> subscribe() {
    ensureExists();
    List<RawJournalEntry> lastEntries = context.spi().readLast(key, 1);
    String startingCheckpoint = lastEntries.isEmpty() ? null : lastEntries.getLast().id();
    return buildBlockingSubscription(startingCheckpoint, List.of());
  }

  @Override
  public BlockingSubscription<JournalEntry<T>> subscribeAfter(String afterId) {
    ensureExists();
    return buildBlockingSubscription(afterId, List.of());
  }

  @Override
  public BlockingSubscription<JournalEntry<T>> subscribeLast(int count) {
    ensureExists();
    List<RawJournalEntry> preload = context.spi().readLast(key, count);
    String startingCheckpoint = preload.isEmpty() ? null : preload.getLast().id();
    return buildBlockingSubscription(startingCheckpoint, preload);
  }

  @Override
  public Subscription subscribe(Subscriber<JournalEntry<T>> subscriber) {
    ensureExists();
    List<RawJournalEntry> lastEntries = context.spi().readLast(key, 1);
    String startingCheckpoint = lastEntries.isEmpty() ? null : lastEntries.getLast().id();
    return buildCallbackSubscription(startingCheckpoint, List.of(), subscriber);
  }

  @Override
  public Subscription subscribe(Consumer<SubscriberConfig<JournalEntry<T>>> customizer) {
    ensureExists();
    return subscribe(DefaultSubscriberBuilder.from(customizer));
  }

  @Override
  public Subscription subscribeAfter(String afterId, Subscriber<JournalEntry<T>> subscriber) {
    ensureExists();
    return buildCallbackSubscription(afterId, List.of(), subscriber);
  }

  @Override
  public Subscription subscribeAfter(
      String afterId, Consumer<SubscriberConfig<JournalEntry<T>>> customizer) {
    ensureExists();
    return subscribeAfter(afterId, DefaultSubscriberBuilder.from(customizer));
  }

  @Override
  public Subscription subscribeLast(int count, Subscriber<JournalEntry<T>> subscriber) {
    ensureExists();
    List<RawJournalEntry> preload = context.spi().readLast(key, count);
    String startingCheckpoint = preload.isEmpty() ? null : preload.getLast().id();
    return buildCallbackSubscription(startingCheckpoint, preload, subscriber);
  }

  @Override
  public Subscription subscribeLast(
      int count, Consumer<SubscriberConfig<JournalEntry<T>>> customizer) {
    ensureExists();
    return subscribeLast(count, DefaultSubscriberBuilder.from(customizer));
  }

  @Override
  public String key() {
    return key;
  }

  private BlockingSubscription<JournalEntry<T>> buildBlockingSubscription(
      String startingCheckpoint, List<RawJournalEntry> preload) {
    BoundedQueueHandoff<JournalEntry<T>> handoff =
        new BoundedQueueHandoff<>(context.limits().subscriptionQueueCapacity());
    Runnable canceller = startFeeder(handoff, startingCheckpoint, preload);
    return new DefaultBlockingSubscription<>(handoff, canceller, context.shutdownCoordinator());
  }

  private Subscription buildCallbackSubscription(
      String startingCheckpoint,
      List<RawJournalEntry> preload,
      Subscriber<JournalEntry<T>> subscriber) {
    BoundedQueueHandoff<JournalEntry<T>> handoff =
        new BoundedQueueHandoff<>(context.limits().subscriptionQueueCapacity());
    Runnable canceller = startFeeder(handoff, startingCheckpoint, preload);
    var source =
        new DefaultBlockingSubscription<>(handoff, canceller, context.shutdownCoordinator());
    return new CallbackPumpSubscription<>(source, subscriber);
  }

  private Runnable startFeeder(
      BoundedQueueHandoff<JournalEntry<T>> handoff,
      String startingCheckpoint,
      List<RawJournalEntry> preload) {
    AtomicReference<String> checkpoint = new AtomicReference<>(startingCheckpoint);
    AtomicBoolean preloaded = new AtomicBoolean(false);

    return FeederSupport.start(
        key,
        context.notifier()::subscribeToJournal,
        handoff,
        "substrate-journal-feeder",
        () -> runOneIteration(handoff, checkpoint, preloaded, preload));
  }

  private boolean runOneIteration(
      BoundedQueueHandoff<JournalEntry<T>> handoff,
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
      BoundedQueueHandoff<JournalEntry<T>> handoff,
      AtomicReference<String> checkpoint,
      AtomicBoolean preloaded,
      List<RawJournalEntry> preload) {
    if (!preloaded.compareAndSet(false, true)) {
      return;
    }
    for (RawJournalEntry raw : preload) {
      handoff.deliver(decode(raw));
      checkpoint.set(raw.id());
    }
  }

  private boolean readBatchAndPush(
      BoundedQueueHandoff<JournalEntry<T>> handoff, AtomicReference<String> checkpoint) {
    try {
      List<RawJournalEntry> batch = readAfterCheckpoint(checkpoint.get());
      for (RawJournalEntry raw : batch) {
        handoff.deliver(decode(raw));
        checkpoint.set(raw.id());
      }
      return true;
    } catch (JournalExpiredException _) {
      handoff.markExpired();
      return false;
    }
  }

  private boolean drainIfCompleted(
      BoundedQueueHandoff<JournalEntry<T>> handoff, AtomicReference<String> checkpoint) {
    if (!context.spi().isComplete(key)) {
      return false;
    }
    try {
      List<RawJournalEntry> finalBatch = readAfterCheckpoint(checkpoint.get());
      for (RawJournalEntry raw : finalBatch) {
        handoff.deliver(decode(raw));
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
      return context.spi().readAfter(key, cp);
    }
    return context.spi().readLast(key, Integer.MAX_VALUE);
  }

  private JournalEntry<T> decode(RawJournalEntry raw) {
    return new JournalEntry<>(
        raw.id(),
        raw.key(),
        codec.decode(context.transformer().decode(raw.data())),
        raw.timestamp());
  }
}
