# SNS/SQS backend: Notifier only

## What to build

One Maven module providing an AWS SNS/SQS-backed Notifier implementation. Uses AWS SDK v2
(`software.amazon.awssdk:sns` and `software.amazon.awssdk:sqs`). No Journal or Mailbox —
SNS/SQS is pub/sub only.

### substrate-notifier-sns

Package: `org.jwcarman.substrate.notifier.sns`

**SnsNotifier** — mirrors Odyssey's `SnsOdysseyStreamNotifier`:
- Publish: SNS `publish()` with message `key|payload` (pipe-delimited)
- Receive: creates an ephemeral SQS queue, subscribes it to the SNS topic with
  appropriate IAM policy
- Virtual thread for polling loop (`Thread.ofVirtual()`) — long-polls SQS
- Message parsing: extract nested SNS JSON envelope from SQS message body, parse
  `key|payload` from the SNS message
- Batch message deletion (up to 10 per `DeleteMessageBatch` call)
- Cleanup on stop: unsubscribe SQS queue from SNS topic, delete SQS queue
- Implements `SmartLifecycle`

**SnsNotifierProperties** — `@ConfigurationProperties(prefix = "substrate.notifier.sns")`:
- `topicArn` (String — required, no default)
- `autoCreateTopic` (boolean, default false)
- `sqsMessageRetention` (Duration, default 1m)
- `sqsWaitTimeSeconds` (int, default 20 — SQS long-poll duration)

**SnsNotifierAutoConfiguration**:
- `@AutoConfiguration(before = SubstrateAutoConfiguration.class)`
- `@ConditionalOnClass(SnsClient.class)`
- Creates `SnsNotifier` bean from `SnsClient`, `SqsClient`, and properties

## Acceptance criteria

- [ ] Module compiles and produces a jar
- [ ] `AutoConfiguration.imports` registration exists
- [ ] Auto-config suppresses the in-memory Notifier fallback
- [ ] Properties load defaults from `*-defaults.properties`
- [ ] Unit tests with mocked `SnsClient` and `SqsClient` verify:
  - SNS publish with correct message format
  - SQS queue creation and SNS subscription
  - Message parsing (SNS envelope extraction)
  - Batch deletion
  - Cleanup on stop
- [ ] Integration tests with Testcontainers LocalStack (SNS + SQS) verify:
  - End-to-end: notify → handler receives key and payload
  - Multiple handlers
  - Lifecycle start/stop with cleanup
- [ ] Auto-configuration tests verify bean creation
- [ ] Virtual thread used for SQS polling (not platform thread)
- [ ] Spotless passes
- [ ] All tests pass (`./mvnw verify`)
- [ ] Apache 2.0 license headers on all files
- [ ] Module added to `substrate-bom` and parent POM

## Implementation notes

- Reference Odyssey's SNS module:
  - `/Users/jcarman/IdeaProjects/odyssey/odyssey-notifier-sns/`
- Manual JSON parsing for the SNS envelope (no Jackson dependency) — Odyssey does this
  inline and it works fine.
- The ephemeral SQS queue pattern means each application instance gets its own queue.
  This provides fan-out: every node receives every notification.
- `topicArn` has no sensible default — it must be configured. Auto-create can
  create the topic if needed.
- Testcontainers: use `localstack/localstack:latest` with SNS + SQS services.
