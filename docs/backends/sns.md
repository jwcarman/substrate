# SNS and SQS

`substrate-sns` is a notifier only. It carries Substrate's change notifications through an Amazon
SNS topic, with one SQS queue per node, and stores nothing. Pair it with a storage backend that
has no notifier, most often [DynamoDB](dynamodb.md).

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-sns</artifactId>
</dependency>
```

## What it needs

- **An SNS topic**: its ARN in `substrate.sns.notifier.topic-arn`, or
  `substrate.sns.notifier.auto-create-topic: true` to create one named `substrate-notifications`
  at startup.
- **`SnsClient` and `SqsClient`.** If you don't define them, the module creates both with the AWS
  SDK defaults — region and credentials from the default provider chain.
- **IAM permissions** to publish to the topic; to create, configure and delete SQS queues; and to
  subscribe and unsubscribe them.

## How it works

At startup each node:

1. creates an SQS queue named `substrate-<random UUID>`, with a message retention of
   `sqs-message-retention`;
2. sets the queue's policy so the topic can deliver to it, and subscribes it to the topic;
3. long-polls the queue, waiting up to `sqs-wait-time-seconds` per request.

Every notification is published to the topic, which fans it out to every node's queue. At
shutdown the node unsubscribes and deletes its queue. A node that stops without shutting down
cleanly leaves its queue behind; the subscription keeps delivering to it until you remove it.

Notifications are delivered through two AWS services, so expect higher latency than Redis pub/sub
or NATS. Subscriptions still re-read their primitive once a second, so a slow notification never
delays a change by more than about a second.

## Exactly one notifier

!!! warning
    Unlike the other notifier modules, `substrate-sns` registers its notifier even when another
    module already provides one. If the storage module you pair it with has a notifier (Redis,
    PostgreSQL, Hazelcast, NATS or RabbitMQ), switch that one off, or the application can fail to
    start with two notifiers:

    ```yaml
    substrate:
      postgresql:
        notifier:
          enabled: false
    ```

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.sns.notifier.enabled` | `true` | Use SNS and SQS as the notifier. |
| `substrate.sns.notifier.topic-arn` | — | The topic to publish to. |
| `substrate.sns.notifier.auto-create-topic` | `false` | Create the topic `substrate-notifications` if no ARN is set. |
| `substrate.sns.notifier.sqs-message-retention` | `1m` | How long each node's queue keeps an unread notification. |
| `substrate.sns.notifier.sqs-wait-time-seconds` | `20` | Long-poll wait per receive request, in seconds. |

## Local development

LocalStack provides SNS and SQS locally; Substrate's tests use `localstack/localstack:3.8`.
Define `SnsClient` and `SqsClient` beans pointing at `http://localhost:4566` and set
`auto-create-topic: true`.
