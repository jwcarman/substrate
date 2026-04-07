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
package org.jwcarman.substrate.journal.cassandra;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    String id = journal.append("substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8));

    UUID uuid = UUID.fromString(id);
    assertThat(uuid.version()).isEqualTo(1);
  }

  @Test
  void appendExecutesPreparedStatement() {
    journal.append("substrate:journal:test", "hello".getBytes(StandardCharsets.UTF_8));

    // Constructor executes 0 statements; append executes 1
    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void appendWithZeroTtlUsesInsertWithoutTtl() {
    CassandraJournalSpi noTtlJournal =
        new CassandraJournalSpi(session, "substrate:journal:", "substrate_journal", Duration.ZERO);

    noTtlJournal.append("substrate:journal:test", "data".getBytes(StandardCharsets.UTF_8));

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
    journal.complete("substrate:journal:test");

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
}
