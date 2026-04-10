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
package org.jwcarman.substrate.mongodb.journal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.Binary;
import org.jwcarman.substrate.core.journal.AbstractJournalSpi;
import org.jwcarman.substrate.core.journal.RawJournalEntry;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

public class MongoDbJournalSpi extends AbstractJournalSpi {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_ENTRY_ID = "entryId";
  private static final String FIELD_DATA = "data";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String FIELD_EXPIRE_AT = "expireAt";
  private static final String COMPLETED_ENTRY_ID = "COMPLETED";

  private final MongoTemplate mongoTemplate;
  private final String collectionName;
  private final Duration ttl;

  public MongoDbJournalSpi(
      MongoTemplate mongoTemplate, String prefix, String collectionName, Duration ttl) {
    super(prefix);
    this.mongoTemplate = mongoTemplate;
    this.collectionName = collectionName;
    this.ttl = ttl;
  }

  public void ensureIndexes() {
    var indexOps = mongoTemplate.indexOps(collectionName);

    indexOps.createIndex(
        new CompoundIndexDefinition(new Document(FIELD_KEY, 1).append(FIELD_ENTRY_ID, 1)));

    if (!ttl.isZero()) {
      indexOps.createIndex(new Index().on(FIELD_EXPIRE_AT, Sort.Direction.ASC).expire(0));
    }
  }

  @Override
  public String append(String key, byte[] data, Duration ttl) {
    String entryId = generateEntryId();

    Document doc = new Document();
    doc.put(FIELD_KEY, key);
    doc.put(FIELD_ENTRY_ID, entryId);
    doc.put(FIELD_DATA, new Binary(data));
    doc.put(FIELD_TIMESTAMP, Instant.now());

    if (ttl != null && !ttl.isZero()) {
      doc.put(FIELD_EXPIRE_AT, Instant.now().plus(ttl));
    }

    mongoTemplate.insert(doc, collectionName);
    return entryId;
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    Query query =
        new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_ENTRY_ID).gt(afterId))
            .with(Sort.by(Sort.Direction.ASC, FIELD_ENTRY_ID));

    List<Document> docs = mongoTemplate.find(query, Document.class, collectionName);
    return docs.stream()
        .filter(doc -> !COMPLETED_ENTRY_ID.equals(doc.getString(FIELD_ENTRY_ID)))
        .map(this::mapDocument)
        .toList();
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    // Request extra to account for possible COMPLETED marker
    Query query =
        new Query(Criteria.where(FIELD_KEY).is(key))
            .with(Sort.by(Sort.Direction.DESC, FIELD_ENTRY_ID))
            .limit(count + 1);

    List<Document> docs = mongoTemplate.find(query, Document.class, collectionName);
    List<RawJournalEntry> entries = new ArrayList<>();
    for (Document doc : docs) {
      if (!COMPLETED_ENTRY_ID.equals(doc.getString(FIELD_ENTRY_ID))) {
        entries.add(mapDocument(doc));
      }
    }

    if (entries.size() > count) {
      entries = entries.subList(0, count);
    }
    return entries.reversed();
  }

  @Override
  public void complete(String key, Duration retentionTtl) {
    Document doc = new Document();
    doc.put(FIELD_KEY, key);
    doc.put(FIELD_ENTRY_ID, COMPLETED_ENTRY_ID);
    doc.put(FIELD_TIMESTAMP, Instant.now());

    if (retentionTtl != null && !retentionTtl.isZero()) {
      doc.put(FIELD_EXPIRE_AT, Instant.now().plus(retentionTtl));
    }

    // Remove any existing completion marker before inserting
    Query removeQuery =
        new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_ENTRY_ID).is(COMPLETED_ENTRY_ID));
    mongoTemplate.remove(removeQuery, collectionName);

    mongoTemplate.insert(doc, collectionName);
  }

  @Override
  public boolean isComplete(String key) {
    Query query =
        new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_ENTRY_ID).is(COMPLETED_ENTRY_ID));
    return mongoTemplate.exists(query, collectionName);
  }

  @Override
  public void delete(String key) {
    Query query = new Query(Criteria.where(FIELD_KEY).is(key));
    mongoTemplate.remove(query, collectionName);
  }

  private RawJournalEntry mapDocument(Document doc) {
    Date date = doc.get(FIELD_TIMESTAMP, Date.class);
    Instant timestamp = date != null ? date.toInstant() : null;
    Binary binary = doc.get(FIELD_DATA, Binary.class);
    byte[] data = binary != null ? binary.getData() : new byte[0];
    return new RawJournalEntry(
        doc.getString(FIELD_ENTRY_ID), doc.getString(FIELD_KEY), data, timestamp);
  }
}
