# DynamoDB

`substrate-dynamodb` stores all three primitives in DynamoDB tables. It has no notifier of its
own; on AWS, pair it with [`substrate-sns`](sns.md).

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-dynamodb</artifactId>
</dependency>
```

## What it needs

- **A `DynamoDbClient` bean** (AWS SDK v2). Spring Boot does not create one; define it:

    ```java
    @Bean
    DynamoDbClient dynamoDbClient() {
      return DynamoDbClient.create(); // region and credentials from the default AWS chain
    }
    ```

- **IAM permissions** to read and write the tables below — and to create them, unless you create
  them yourself.

## Tables

| Primitive | Table | Key |
|---|---|---|
| Atom | `substrate_atoms` | partition key `pk` |
| Journal | `substrate_journal` | partition key `key`, sort key `entry_id` |
| Mailbox | `substrate_mailbox` | partition key `key` |

With `auto-create-table` (the default), each table is created at startup if it doesn't exist,
with provisioned throughput of 5 read and 5 write capacity units. For production, create the
tables yourself with the capacity mode you want, and set `auto-create-table` to `false`.

## Enable TTL on every table

!!! warning "Substrate does not turn on DynamoDB's TTL"
    Each item stores its expiry, in epoch seconds, in an attribute named `ttl`, but the module does
    not enable DynamoDB's Time to Live feature on the tables, and there is no sweeper for this
    backend. Enable TTL on the `ttl` attribute of all three tables yourself; otherwise nothing is
    ever deleted:

    ```bash
    aws dynamodb update-time-to-live --table-name substrate_atoms \
        --time-to-live-specification "Enabled=true, AttributeName=ttl"
    ```

Even with TTL enabled, DynamoDB deletes expired items in the background, typically within a few
days. Until then:

- atoms and mailboxes are already treated as expired, because reads compare `ttl` with the
  current time;
- journal entries are not checked on read, so an expired entry can still be replayed by
  `subscribeLast` or `subscribeAfter`.

The Journal lifecycle is partly enforced; the retention TTL applies only to the completion marker.
See [How backends differ](../primitives/journal.md#how-backends-differ).

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.dynamodb.atom.enabled` | `true` | Serve Atom from DynamoDB. |
| `substrate.dynamodb.atom.prefix` | `substrate:atom:` | Key prefix for atoms. |
| `substrate.dynamodb.atom.table-name` | `substrate_atoms` | The atom table. |
| `substrate.dynamodb.atom.auto-create-table` | `true` | Create the table at startup. |
| `substrate.dynamodb.journal.enabled` | `true` | Serve Journal from DynamoDB. |
| `substrate.dynamodb.journal.prefix` | `substrate:journal:` | Key prefix for journals. |
| `substrate.dynamodb.journal.table-name` | `substrate_journal` | The journal table. |
| `substrate.dynamodb.journal.auto-create-table` | `true` | Create the table at startup. |
| `substrate.dynamodb.journal.ttl` | `24h` | Not used. |
| `substrate.dynamodb.mailbox.enabled` | `true` | Serve Mailbox from DynamoDB. |
| `substrate.dynamodb.mailbox.prefix` | `substrate:mailbox:` | Key prefix for mailboxes. |
| `substrate.dynamodb.mailbox.table-name` | `substrate_mailbox` | The mailbox table. |
| `substrate.dynamodb.mailbox.auto-create-table` | `true` | Create the table at startup. |
| `substrate.dynamodb.mailbox.default-ttl` | `5m` | Not used; each mailbox's TTL comes from `create`. |

## Local development

LocalStack provides DynamoDB locally; Substrate's tests use `localstack/localstack:3.8`.

```bash
docker run -p 4566:4566 localstack/localstack:3.8
```

```java
@Bean
DynamoDbClient dynamoDbClient() {
  return DynamoDbClient.builder()
      .endpointOverride(URI.create("http://localhost:4566"))
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .build();
}
```
