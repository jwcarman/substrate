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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.Binary;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.RawAtom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class MongoDbAtomSpi extends AbstractAtomSpi {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_EXPIRE_AT = "expireAt";

  private final MongoTemplate mongoTemplate;
  private final String collectionName;

  public MongoDbAtomSpi(MongoTemplate mongoTemplate, String prefix, String collectionName) {
    super(prefix);
    this.mongoTemplate = mongoTemplate;
    this.collectionName = collectionName;
  }

  public void ensureIndexes() {
    var indexOps = mongoTemplate.indexOps(collectionName);
    indexOps.createIndex(new Index().on(FIELD_KEY, Sort.Direction.ASC).unique());
    indexOps.createIndex(new Index().on(FIELD_EXPIRE_AT, Sort.Direction.ASC).expire(0));
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    Document doc = new Document();
    doc.put(FIELD_KEY, key);
    doc.put(FIELD_VALUE, new Binary(value));
    doc.put(FIELD_TOKEN, token);
    doc.put(FIELD_EXPIRE_AT, Instant.now().plus(ttl));
    try {
      mongoTemplate.insert(doc, collectionName);
    } catch (DuplicateKeyException _) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    Query query =
        new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_EXPIRE_AT).gt(Instant.now()));
    Document doc = mongoTemplate.findOne(query, Document.class, collectionName);
    if (doc == null) {
      return Optional.empty();
    }
    byte[] bytes = doc.get(FIELD_VALUE, Binary.class).getData();
    String token = doc.getString(FIELD_TOKEN);
    return Optional.of(new RawAtom(bytes, token));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    Query query =
        new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_EXPIRE_AT).gt(Instant.now()));
    Update update =
        new Update()
            .set(FIELD_VALUE, new Binary(value))
            .set(FIELD_TOKEN, token)
            .set(FIELD_EXPIRE_AT, Instant.now().plus(ttl));
    var result = mongoTemplate.updateFirst(query, update, collectionName);
    return result.getModifiedCount() > 0;
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    Query query =
        new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_EXPIRE_AT).gt(Instant.now()));
    Update update = new Update().set(FIELD_EXPIRE_AT, Instant.now().plus(ttl));
    var result = mongoTemplate.updateFirst(query, update, collectionName);
    return result.getModifiedCount() > 0;
  }

  @Override
  public void delete(String key) {
    Query query = new Query(Criteria.where(FIELD_KEY).is(key));
    mongoTemplate.remove(query, collectionName);
  }

  @Override
  public boolean exists(String key) {
    Query query =
        new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_EXPIRE_AT).gt(Instant.now()));
    return mongoTemplate.exists(query, collectionName);
  }
}
