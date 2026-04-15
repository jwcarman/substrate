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
package org.jwcarman.substrate.mongodb.mailbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.Binary;
import org.jwcarman.substrate.core.mailbox.AbstractMailboxSpi;
import org.jwcarman.substrate.mailbox.MailboxExpiredException;
import org.jwcarman.substrate.mailbox.MailboxFullException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class MongoDbMailboxSpi extends AbstractMailboxSpi {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_EXPIRE_AT = "expireAt";

  private final MongoTemplate mongoTemplate;
  private final String collectionName;

  public MongoDbMailboxSpi(MongoTemplate mongoTemplate, String prefix, String collectionName) {
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
  public void create(String key, Duration ttl) {
    Query query = new Query(Criteria.where(FIELD_KEY).is(key));
    Update update =
        new Update()
            .set(FIELD_KEY, key)
            .unset(FIELD_VALUE)
            .set(FIELD_EXPIRE_AT, Instant.now().plus(ttl));
    mongoTemplate.upsert(query, update, collectionName);
  }

  @Override
  public void deliver(String key, byte[] value) {
    Query query =
        new Query(
            Criteria.where(FIELD_KEY)
                .is(key)
                .and(FIELD_EXPIRE_AT)
                .gt(Instant.now())
                .and(FIELD_VALUE)
                .exists(false));
    Update update = new Update().set(FIELD_VALUE, new Binary(value));

    var result = mongoTemplate.updateFirst(query, update, collectionName);
    if (result.getMatchedCount() == 0) {
      Query aliveQuery =
          new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_EXPIRE_AT).gt(Instant.now()));
      if (mongoTemplate.exists(aliveQuery, collectionName)) {
        throw new MailboxFullException(key);
      }
      throw new MailboxExpiredException(key);
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    Query query =
        new Query(Criteria.where(FIELD_KEY).is(key).and(FIELD_EXPIRE_AT).gt(Instant.now()));
    Document doc = mongoTemplate.findOne(query, Document.class, collectionName);
    if (doc == null) {
      throw new MailboxExpiredException(key);
    }
    if (!doc.containsKey(FIELD_VALUE)) {
      return Optional.empty();
    }
    Binary binary = doc.get(FIELD_VALUE, Binary.class);
    if (binary == null) {
      return Optional.empty();
    }
    return Optional.of(binary.getData());
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
