# RabbitMQ

`substrate-rabbitmq` provides two things: a Journal stored in RabbitMQ Streams, and a notifier
that uses a fanout exchange. The notifier is the more common reason to add it — paired with a
storage backend that has no notifier of its own, such as MongoDB or Cassandra.

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-rabbitmq</artifactId>
</dependency>
```

## What it needs

- **For the notifier:** Spring AMQP's `ConnectionFactory` and `RabbitTemplate`, which Spring Boot
  creates from `spring.rabbitmq.*` (`substrate-rabbitmq` brings `spring-boot-starter-amqp`).
- **For the Journal:** a RabbitMQ Stream client `com.rabbitmq.stream.Environment` bean, and the
  `rabbitmq_stream` plugin enabled on the broker. Define the bean yourself, or add
  `spring-rabbit-stream` so Spring Boot configures it from `spring.rabbitmq.stream.*`.

RabbitMQ 4 is what Substrate's tests run against.

## The notifier

The notifier declares a durable fanout exchange, `substrate-notify`. Each node declares its own
server-named, exclusive, auto-delete queue bound to it, so every node receives every notification,
and a node's queue disappears when it disconnects.

To use only the notifier with another module's Journal, switch the Journal off:

```yaml
substrate:
  rabbitmq:
    journal:
      enabled: false
```

## The Journal

Each journal is its own RabbitMQ stream, created on first append with the configured maximum age
(`max-age`, 24 hours) and size (`max-length-bytes`, 1 GiB). Those limits replace the per-entry TTL,
which this backend ignores; the retention TTL is also ignored, and the rest of the lifecycle is
partly enforced. See [How backends differ](../primitives/journal.md#how-backends-differ).

!!! warning "Reads scan the whole stream"
    Each read consumes the journal's stream from the beginning and filters in memory, and every
    subscription reads about once a second. Read cost grows with journal length times the number of
    subscribers. Use this Journal for short streams; for long ones, choose another backend.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.rabbitmq.journal.enabled` | `true` | Serve Journal from RabbitMQ Streams. |
| `substrate.rabbitmq.journal.prefix` | `substrate:journal:` | Prefix of each journal's stream name. |
| `substrate.rabbitmq.journal.max-age` | `24h` | Maximum age of stream messages. |
| `substrate.rabbitmq.journal.max-length-bytes` | `1073741824` | Maximum size of each stream, in bytes. |
| `substrate.rabbitmq.notifier.enabled` | `true` | Use RabbitMQ as the notifier. |
| `substrate.rabbitmq.notifier.exchange-name` | `substrate-notify` | The fanout exchange. |

## Local development

```bash
docker run -p 5672:5672 -p 5552:5552 -p 15672:15672 rabbitmq:4-management
docker exec <container> rabbitmq-plugins enable rabbitmq_stream
```
