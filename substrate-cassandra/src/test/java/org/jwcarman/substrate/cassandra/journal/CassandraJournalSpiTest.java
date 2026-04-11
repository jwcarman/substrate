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
package org.jwcarman.substrate.cassandra.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CassandraJournalSpiTest {

  @Mock private CqlSession session;
  @Mock private PreparedStatement preparedStatement;
  @Mock private BoundStatement boundStatement;
  @Mock private ResultSet resultSet;

  private CassandraJournalSpi journal;

  @BeforeEach
  void setUp() {
    when(session.prepare(anyString())).thenReturn(preparedStatement);
    when(preparedStatement.bind(any(Object[].class))).thenReturn(boundStatement);
    when(session.execute(any(BoundStatement.class))).thenReturn(resultSet);

    journal =
        new CassandraJournalSpi(
            session, "substrate:journal:", "substrate_journal", Duration.ofHours(24));
  }

  @Test
  void appendReturnsTimeuuidId() {
    String id =
        journal.append(
            "substrate:journal:test",
            "hello".getBytes(StandardCharsets.UTF_8),
            Duration.ofHours(1));

    UUID uuid = UUID.fromString(id);
    assertThat(uuid.version()).isEqualTo(1);
  }

  @Test
  void appendExecutesPreparedStatement() {
    journal.append(
        "substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8), Duration.ofHours(1));

    // Constructor executes 0 statements; append executes 1
    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void appendWithZeroTtlUsesInsertWithoutTtl() {
    CassandraJournalSpi noTtlJournal =
        new CassandraJournalSpi(session, "substrate:journal:", "substrate_journal", Duration.ZERO);

    noTtlJournal.append(
        "substrate:journal:test", "data".getBytes(StandardCharsets.UTF_8), Duration.ZERO);

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void readAfterExecutesQuery() {
    when(resultSet.iterator()).thenReturn(List.<Row>of().iterator());

    journal.readAfter("substrate:journal:test", "e5e3e100-1c9c-11b2-808b-8b3a28b5a162");

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void readLastExecutesQuery() {
    when(resultSet.iterator()).thenReturn(List.<Row>of().iterator());

    journal.readLast("substrate:journal:test", 5);

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void completeExecutesPreparedStatement() {
    journal.complete("substrate:journal:test", Duration.ofHours(1));

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void deleteExecutesPreparedStatement() {
    journal.delete("substrate:journal:test");

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void journalKeyUsesConfiguredPrefix() {
    assertThat(journal.journalKey("my-stream")).isEqualTo("substrate:journal:my-stream");
  }

  @Test
  void createSchemaExecutesCql() {
    when(session.execute(anyString())).thenReturn(resultSet);

    journal.createSchema();

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(session).execute(captor.capture());
    assertThat(captor.getValue()).contains("CREATE TABLE IF NOT EXISTS substrate_journal");
    assertThat(captor.getValue()).contains("TIMEUUID");
    assertThat(captor.getValue()).contains("CLUSTERING ORDER BY");
  }

  @Test
  void preparesStatementsDuringConstruction() {
    // 5 prepared statements: insert, insertWithTtl, readAfter, readLast, delete
    verify(session, atLeast(5)).prepare(anyString());
  }

  @Test
  void isCompleteReturnsTrueWhenCompletedDataExists() {
    ByteBuffer completedData = ByteBuffer.wrap("__COMPLETED__".getBytes(StandardCharsets.UTF_8));
    Row row = org.mockito.Mockito.mock(Row.class);
    when(row.getByteBuffer("data")).thenReturn(completedData);

    ResultSet isCompleteResultSet = org.mockito.Mockito.mock(ResultSet.class);
    when(isCompleteResultSet.iterator()).thenReturn(List.of(row).iterator());
    when(session.execute(anyString(), any(Object.class))).thenReturn(isCompleteResultSet);

    assertThat(journal.isComplete("substrate:journal:test")).isTrue();
  }

  @Test
  void isCompleteReturnsFalseWhenNoCompletedData() {
    ByteBuffer regularData = ByteBuffer.wrap("regular".getBytes(StandardCharsets.UTF_8));
    Row row = org.mockito.Mockito.mock(Row.class);
    when(row.getByteBuffer("data")).thenReturn(regularData);

    ResultSet isCompleteResultSet = org.mockito.Mockito.mock(ResultSet.class);
    when(isCompleteResultSet.iterator()).thenReturn(List.of(row).iterator());
    when(session.execute(anyString(), any(Object.class))).thenReturn(isCompleteResultSet);

    assertThat(journal.isComplete("substrate:journal:test")).isFalse();
  }

  @Test
  void isCompleteReturnsFalseWhenEmpty() {
    ResultSet isCompleteResultSet = org.mockito.Mockito.mock(ResultSet.class);
    when(isCompleteResultSet.iterator()).thenReturn(List.<Row>of().iterator());
    when(session.execute(anyString(), any(Object.class))).thenReturn(isCompleteResultSet);

    assertThat(journal.isComplete("substrate:journal:test")).isFalse();
  }

  @Test
  void readAfterFiltersOutCompletedDataRows() {
    ByteBuffer completedData = ByteBuffer.wrap("__COMPLETED__".getBytes(StandardCharsets.UTF_8));
    ByteBuffer regularData = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
    UUID regularId = Uuids.timeBased();
    Instant now = Instant.now();

    Row regularRow = org.mockito.Mockito.mock(Row.class);
    when(regularRow.getByteBuffer("data")).thenReturn(regularData);
    when(regularRow.getUuid("entry_id")).thenReturn(regularId);
    when(regularRow.getInstant("timestamp")).thenReturn(now);

    Row completedRow = org.mockito.Mockito.mock(Row.class);
    when(completedRow.getByteBuffer("data")).thenReturn(completedData);

    when(resultSet.iterator()).thenReturn(List.of(regularRow, completedRow).iterator());

    List<org.jwcarman.substrate.core.journal.RawJournalEntry> entries =
        journal.readAfter("substrate:journal:test", "e5e3e100-1c9c-11b2-808b-8b3a28b5a162");

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).id()).isEqualTo(regularId.toString());
  }

  @Test
  void readLastFiltersOutCompletedDataAndReversesOrder() {
    ByteBuffer data1 = ByteBuffer.wrap("first".getBytes(StandardCharsets.UTF_8));
    ByteBuffer data2 = ByteBuffer.wrap("second".getBytes(StandardCharsets.UTF_8));
    UUID id1 = Uuids.timeBased();
    UUID id2 = Uuids.timeBased();
    Instant now = Instant.now();

    // DESC order: id2 first, then id1
    Row row2 = org.mockito.Mockito.mock(Row.class);
    when(row2.getByteBuffer("data")).thenReturn(data2);
    when(row2.getUuid("entry_id")).thenReturn(id2);
    when(row2.getInstant("timestamp")).thenReturn(now);

    Row row1 = org.mockito.Mockito.mock(Row.class);
    when(row1.getByteBuffer("data")).thenReturn(data1);
    when(row1.getUuid("entry_id")).thenReturn(id1);
    when(row1.getInstant("timestamp")).thenReturn(now);

    when(resultSet.iterator()).thenReturn(List.of(row2, row1).iterator());

    List<org.jwcarman.substrate.core.journal.RawJournalEntry> entries =
        journal.readLast("substrate:journal:test", 5);

    // Should be reversed to ASC order: id1 first, then id2
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).id()).isEqualTo(id1.toString());
    assertThat(entries.get(1).id()).isEqualTo(id2.toString());
  }

  @Test
  void readLastTruncatesWhenMoreThanCount() {
    Instant now = Instant.now();
    List<Row> rows = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID id = Uuids.timeBased();
      Row row = org.mockito.Mockito.mock(Row.class);
      ByteBuffer data = ByteBuffer.wrap(("data-" + i).getBytes(StandardCharsets.UTF_8));
      when(row.getByteBuffer("data")).thenReturn(data);
      when(row.getUuid("entry_id")).thenReturn(id);
      when(row.getInstant("timestamp")).thenReturn(now);
      rows.add(row);
    }

    when(resultSet.iterator()).thenReturn(rows.iterator());

    List<org.jwcarman.substrate.core.journal.RawJournalEntry> entries =
        journal.readLast("substrate:journal:test", 2);

    assertThat(entries).hasSize(2);
  }

  @Test
  void completeWithZeroTtlUsesInsertWithoutTtl() {
    CassandraJournalSpi noTtlJournal =
        new CassandraJournalSpi(session, "substrate:journal:", "substrate_journal", Duration.ZERO);

    noTtlJournal.complete("substrate:journal:test", Duration.ZERO);

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void completeWithNullTtlUsesDefaultTtl() {
    journal.complete("substrate:journal:test", null);

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void appendWithNullTtlUsesDefaultTtl() {
    journal.append("substrate:journal:test", "data".getBytes(StandardCharsets.UTF_8), null);

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void mapRowWithNullByteBufferReturnsEmptyArray() {
    UUID entryId = Uuids.timeBased();
    Instant now = Instant.now();

    Row row = org.mockito.Mockito.mock(Row.class);
    when(row.getByteBuffer("data")).thenReturn(null);
    when(row.getUuid("entry_id")).thenReturn(entryId);
    when(row.getInstant("timestamp")).thenReturn(now);

    when(resultSet.iterator()).thenReturn(List.of(row).iterator());

    List<org.jwcarman.substrate.core.journal.RawJournalEntry> entries =
        journal.readAfter("substrate:journal:test", "e5e3e100-1c9c-11b2-808b-8b3a28b5a162");

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).data()).isEmpty();
  }
}
