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
package org.jwcarman.substrate.core.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.substrate.core.memory.notifier.InMemoryNotifier;
import tools.jackson.databind.json.JsonMapper;

class DefaultNotifierTest {

  private static final CodecFactory CODEC_FACTORY =
      new JacksonCodecFactory(JsonMapper.builder().build());

  private DefaultNotifier notifier;

  @BeforeEach
  void setUp() {
    notifier = new DefaultNotifier(new InMemoryNotifier(), CODEC_FACTORY);
  }

  @Test
  void atomChangedRoutesToAtomSubscriber() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToAtom("key1", received::add);

    notifier.notifyAtomChanged("key1");

    assertThat(received).hasSize(1);
    assertThat(received.getFirst()).isInstanceOf(Notification.Changed.class);
    assertThat(received.getFirst().key()).isEqualTo("key1");
  }

  @Test
  void atomDeletedRoutesToAtomSubscriber() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToAtom("key1", received::add);

    notifier.notifyAtomDeleted("key1");

    assertThat(received).hasSize(1);
    assertThat(received.getFirst()).isInstanceOf(Notification.Deleted.class);
  }

  @Test
  void journalChangedRoutesToJournalSubscriber() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToJournal("key1", received::add);

    notifier.notifyJournalChanged("key1");

    assertThat(received).hasSize(1);
    assertThat(received.getFirst()).isInstanceOf(Notification.Changed.class);
  }

  @Test
  void journalCompletedRoutesToJournalSubscriber() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToJournal("key1", received::add);

    notifier.notifyJournalCompleted("key1");

    assertThat(received).hasSize(1);
    assertThat(received.getFirst()).isInstanceOf(Notification.Completed.class);
  }

  @Test
  void journalDeletedRoutesToJournalSubscriber() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToJournal("key1", received::add);

    notifier.notifyJournalDeleted("key1");

    assertThat(received).hasSize(1);
    assertThat(received.getFirst()).isInstanceOf(Notification.Deleted.class);
  }

  @Test
  void mailboxChangedRoutesToMailboxSubscriber() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToMailbox("key1", received::add);

    notifier.notifyMailboxChanged("key1");

    assertThat(received).hasSize(1);
    assertThat(received.getFirst()).isInstanceOf(Notification.Changed.class);
  }

  @Test
  void mailboxDeletedRoutesToMailboxSubscriber() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToMailbox("key1", received::add);

    notifier.notifyMailboxDeleted("key1");

    assertThat(received).hasSize(1);
    assertThat(received.getFirst()).isInstanceOf(Notification.Deleted.class);
  }

  @Test
  void notificationForDifferentKeyNotDelivered() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToAtom("key1", received::add);

    notifier.notifyAtomChanged("key2");

    assertThat(received).isEmpty();
  }

  @Test
  void notificationForDifferentTypeNotDelivered() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToAtom("key1", received::add);

    notifier.notifyJournalChanged("key1");

    assertThat(received).isEmpty();
  }

  @Test
  void multipleHandlersForSameKeyAllReceive() {
    List<Notification> handler1 = new ArrayList<>();
    List<Notification> handler2 = new ArrayList<>();
    notifier.subscribeToAtom("key1", handler1::add);
    notifier.subscribeToAtom("key1", handler2::add);

    notifier.notifyAtomChanged("key1");

    assertThat(handler1).hasSize(1);
    assertThat(handler2).hasSize(1);
  }

  @Test
  void unsubscribeStopsDelivery() {
    List<Notification> received = new ArrayList<>();
    NotifierSubscription sub = notifier.subscribeToAtom("key1", received::add);

    notifier.notifyAtomChanged("key1");
    assertThat(received).hasSize(1);

    sub.cancel();

    notifier.notifyAtomChanged("key1");
    assertThat(received).hasSize(1);
  }

  @Test
  void handlerExceptionDoesNotBreakOtherHandlers() {
    List<Notification> received = new ArrayList<>();
    notifier.subscribeToAtom(
        "key1",
        n -> {
          throw new RuntimeException("boom");
        });
    notifier.subscribeToAtom("key1", received::add);

    notifier.notifyAtomChanged("key1");

    assertThat(received).hasSize(1);
  }

  @Test
  void malformedPayloadIsDropped() {
    InMemoryNotifier spi = new InMemoryNotifier();
    DefaultNotifier localNotifier = new DefaultNotifier(spi, CODEC_FACTORY);

    List<Notification> received = new ArrayList<>();
    localNotifier.subscribeToAtom("key1", received::add);

    spi.notify(new byte[] {0, 1, 2, 3});

    assertThat(received).isEmpty();
  }

  @Test
  void notificationToNonSubscribedKeyDroppedSilently() {
    AtomicReference<Notification> captured = new AtomicReference<>();
    notifier.subscribeToAtom("key1", captured::set);

    notifier.notifyAtomChanged("key-nobody-cares-about");

    assertThat(captured.get()).isNull();
  }
}
