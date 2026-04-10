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
package org.jwcarman.substrate.mongodb.atom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class MongoDbAtomSpiTest {

  @Mock private MongoTemplate mongoTemplate;

  private MongoDbAtomSpi spi;

  @BeforeEach
  void setUp() {
    spi = new MongoDbAtomSpi(mongoTemplate, "substrate:atom:", "substrate_atom");
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(spi.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void createInsertsDocument() {
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);

    spi.create("substrate:atom:test", value, "token-1", Duration.ofMinutes(5));

    verify(mongoTemplate).insert(any(Document.class), eq("substrate_atom"));
  }

  @Test
  void createThrowsAtomAlreadyExistsOnDuplicateKey() {
    byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
    when(mongoTemplate.insert(any(Document.class), eq("substrate_atom")))
        .thenThrow(new DuplicateKeyException("dup key"));

    assertThrows(
        AtomAlreadyExistsException.class,
        () -> spi.create("substrate:atom:test", value, "token-1", Duration.ofMinutes(5)));
  }

  @Test
  void readReturnsValueWhenPresent() {
    Document doc = new Document();
    doc.put("key", "substrate:atom:test");
    doc.put("value", new Binary("hello".getBytes(StandardCharsets.UTF_8)));
    doc.put("token", "token-1");
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("substrate_atom")))
        .thenReturn(doc);

    Optional<RawAtom> result = spi.read("substrate:atom:test");

    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    assertThat(result.get().token()).isEqualTo("token-1");
  }

  @Test
  void readReturnsEmptyWhenAbsent() {
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("substrate_atom")))
        .thenReturn(null);

    assertThat(spi.read("substrate:atom:test")).isEmpty();
  }

  @Test
  void setReturnsTrueWhenModified() {
    var updateResult = com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("substrate_atom")))
        .thenReturn(updateResult);

    boolean result =
        spi.set(
            "substrate:atom:test",
            "updated".getBytes(StandardCharsets.UTF_8),
            "token-2",
            Duration.ofMinutes(5));

    assertThat(result).isTrue();
  }

  @Test
  void setReturnsFalseWhenNotModified() {
    var updateResult = com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("substrate_atom")))
        .thenReturn(updateResult);

    boolean result =
        spi.set(
            "substrate:atom:test",
            "value".getBytes(StandardCharsets.UTF_8),
            "token-1",
            Duration.ofMinutes(5));

    assertThat(result).isFalse();
  }

  @Test
  void touchReturnsTrueWhenModified() {
    var updateResult = com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("substrate_atom")))
        .thenReturn(updateResult);

    assertThat(spi.touch("substrate:atom:test", Duration.ofMinutes(5))).isTrue();
  }

  @Test
  void touchReturnsFalseWhenNotModified() {
    var updateResult = com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("substrate_atom")))
        .thenReturn(updateResult);

    assertThat(spi.touch("substrate:atom:test", Duration.ofMinutes(5))).isFalse();
  }

  @Test
  void deleteRemovesDocument() {
    spi.delete("substrate:atom:test");

    verify(mongoTemplate).remove(any(Query.class), eq("substrate_atom"));
  }

  @Test
  void sweepReturnsZero() {
    assertThat(spi.sweep(100)).isZero();
  }
}
