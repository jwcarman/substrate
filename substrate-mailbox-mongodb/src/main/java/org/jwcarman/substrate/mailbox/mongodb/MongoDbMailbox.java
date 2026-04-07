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
package org.jwcarman.substrate.mailbox.mongodb;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.jwcarman.substrate.spi.AbstractMailbox;
import org.jwcarman.substrate.spi.Notifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class MongoDbMailbox extends AbstractMailbox {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_EXPIRE_AT = "expireAt";

  private final MongoTemplate mongoTemplate;
  private final Notifier notifier;
  private final String collectionName;
  private final Duration defaultTtl;
  private final ConcurrentMap<String, CompletableFuture<String>> pending =
      new ConcurrentHashMap<>();

  public MongoDbMailbox(
      MongoTemplate mongoTemplate,
      Notifier notifier,
      String prefix,
      String collectionName,
      Duration defaultTtl) {
    super(prefix);
    this.mongoTemplate = mongoTemplate;
    this.notifier = notifier;
    this.collectionName = collectionName;
    this.defaultTtl = defaultTtl;
    this.notifier.subscribe(this::onNotification);
  }

  public void ensureIndexes() {
    var indexOps = mongoTemplate.indexOps(collectionName);

    indexOps.createIndex(new Index().on(FIELD_KEY, Sort.Direction.ASC).unique());

    if (!defaultTtl.isZero()) {
      indexOps.createIndex(new Index().on(FIELD_EXPIRE_AT, Sort.Direction.ASC).expire(0));
    }
  }

  @Override
  public void deliver(String key, String value) {
    Query query = new Query(Criteria.where(FIELD_KEY).is(key));
    Update update = new Update().set(FIELD_VALUE, value);

    if (!defaultTtl.isZero()) {
      update.set(FIELD_EXPIRE_AT, Instant.now().plus(defaultTtl));
    }

    mongoTemplate.upsert(query, update, collectionName);
    notifier.notify(key, value);
  }

  @Override
  public CompletableFuture<String> await(String key, Duration timeout) {
    String existing = getValueFromMongo(key);
    if (existing != null) {
      return CompletableFuture.completedFuture(existing);
    }

    CompletableFuture<String> future = pending.computeIfAbsent(key, k -> new CompletableFuture<>());

    // Double-check in case deliver() was called between our get and computeIfAbsent
    String deliveredAfter = getValueFromMongo(key);
    if (deliveredAfter != null) {
      future.complete(deliveredAfter);
      pending.remove(key);
    }

    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void delete(String key) {
    Query query = new Query(Criteria.where(FIELD_KEY).is(key));
    mongoTemplate.remove(query, collectionName);
    CompletableFuture<String> future = pending.remove(key);
    if (future != null) {
      future.cancel(false);
    }
  }

  private String getValueFromMongo(String key) {
    Query query = new Query(Criteria.where(FIELD_KEY).is(key));
    Document doc = mongoTemplate.findOne(query, Document.class, collectionName);
    if (doc != null && doc.containsKey(FIELD_VALUE)) {
      return doc.getString(FIELD_VALUE);
    }
    return null;
  }

  private void onNotification(String key, String payload) {
    CompletableFuture<String> future = pending.remove(key);
    if (future != null) {
      future.complete(payload);
    }
  }
}
