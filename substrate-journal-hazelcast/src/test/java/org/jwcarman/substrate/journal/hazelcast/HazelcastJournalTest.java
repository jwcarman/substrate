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
package org.jwcarman.substrate.journal.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.ringbuffer.Ringbuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class HazelcastJournalSpiTest {

  @Mock private HazelcastInstance hazelcastInstance;
  @Mock private Ringbuffer<String> ringbuffer;
  @Mock private IMap<String, Boolean> completedMap;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private HazelcastJournalSpi journal;

  @BeforeEach
  void setUp() {
    journal = new HazelcastJournalSpi(hazelcastInstance, objectMapper, "substrate:journal:", 100);
  }

  @Test
  void appendAddsToRingbufferAndReturnsSequenceId() {
    when(hazelcastInstance.<String>getRingbuffer("test-key")).thenReturn(ringbuffer);
    when(ringbuffer.add(anyString())).thenReturn(42L);

    String id = journal.append("test-key", "hello".getBytes(StandardCharsets.UTF_8));

    assertThat(id).isEqualTo("42");
    verify(ringbuffer).add(anyString());
  }

  @Test
  void completeStoresMarkerInMap() {
    when(hazelcastInstance.<String, Boolean>getMap("substrate-journal-completed"))
        .thenReturn(completedMap);

    journal.complete("test-key");

    verify(completedMap).put("test-key", Boolean.TRUE);
  }

  @Test
  void deleteDestroysRingbufferAndRemovesCompletionMarker() {
    when(hazelcastInstance.<String>getRingbuffer("test-key")).thenReturn(ringbuffer);
    when(hazelcastInstance.<String, Boolean>getMap("substrate-journal-completed"))
        .thenReturn(completedMap);

    journal.delete("test-key");

    verify(ringbuffer).destroy();
    verify(completedMap).remove("test-key");
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }
}
