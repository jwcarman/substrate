# Substrate

[![CI](https://github.com/jwcarman/substrate/actions/workflows/maven.yml/badge.svg)](https://github.com/jwcarman/substrate/actions/workflows/maven.yml)
[![CodeQL](https://github.com/jwcarman/substrate/actions/workflows/github-code-scanning/codeql/badge.svg)](https://github.com/jwcarman/substrate/actions/workflows/github-code-scanning/codeql)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https://raw.githubusercontent.com/jwcarman/substrate/main/pom.xml&query=//*[local-name()='java.version']/text()&label=Java&color=orange)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/org.jwcarman.substrate/substrate-core)](https://central.sonatype.com/artifact/org.jwcarman.substrate/substrate-core)

[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_substrate&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jwcarman_substrate)

Distributed primitives for Java, on the infrastructure you already run. Substrate gives a
clustered Spring Boot application three small distributed data structures — **Atom**, **Journal**
and **Mailbox** — that behave the same whether they are stored in Redis, PostgreSQL, NATS,
DynamoDB or any of the other supported backends.

**Documentation: <https://jwcarman.github.io/substrate/>**

- **Atom** — a distributed `AtomicReference` with a lease: one current value, compare-and-set and
  change notification.
- **Journal** — an ordered, append-only stream that can be replayed from a checkpoint.
- **Mailbox** — a distributed `CompletableFuture`: one side waits, any node delivers one value.

All three share one subscription model: block on `next(Duration)` from a virtual thread, or hand
over a callback, and handle every outcome as a case of a sealed `NextResult`.

## Requirements

- Java 25+
- Spring Boot 4.x for auto-configuration (the primitives also work without Spring)
- [Codec](https://jwcarman.github.io/codec/) 0.10.0+

## Quick start

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.jwcarman.substrate</groupId>
            <artifactId>substrate-bom</artifactId>
            <version>0.11.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.jwcarman.codec</groupId>
            <artifactId>codec-bom</artifactId>
            <version>0.10.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.jwcarman.substrate</groupId>
        <artifactId>substrate-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.jwcarman.codec</groupId>
        <artifactId>codec-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.jwcarman.codec</groupId>
        <artifactId>codec-jackson</artifactId>
    </dependency>
    <dependency>
        <groupId>org.jwcarman.substrate</groupId>
        <artifactId>substrate-redis</artifactId>
    </dependency>
</dependencies>
```

The codec starter is required: without a `CodecFactory` bean, no factories are registered.

```java
Atom<Presence> presence =
    atoms.create("presence:" + userId, Presence.class, Presence.online(), Duration.ofMinutes(5));

Journal<OrderEvent> journal = journals.create("orders:" + orderId, OrderEvent.class, Duration.ofHours(1));
journal.append(new OrderEvent("created"), Duration.ofDays(1));

Mailbox<Approval> mailbox = mailboxes.create("approval:" + requestId, Approval.class, Duration.ofMinutes(5));
```

[Getting Started](https://jwcarman.github.io/substrate/guides/getting-started/) walks through each
one.

## Backends

One module per technology. Add it to the classpath and Spring Boot registers whatever it
supports; any primitive no module supports falls back to an in-memory implementation, with a
warning.

| Backend | Module | Atom | Journal | Mailbox | Notifier |
|---|---|:---:|:---:|:---:|:---:|
| Redis | `substrate-redis` | ✓ | ✓ | ✓ | ✓ |
| PostgreSQL | `substrate-postgresql` | ✓ | ✓ | ✓ | ✓ |
| Hazelcast | `substrate-hazelcast` | ✓ | ✓ | ✓ | ✓ |
| NATS | `substrate-nats` | ✓ | ✓ | ✓ | ✓ |
| MongoDB | `substrate-mongodb` | ✓ | ✓ | ✓ | — |
| DynamoDB | `substrate-dynamodb` | ✓ | ✓ | ✓ | — |
| Cassandra | `substrate-cassandra` | ✓ | ✓ | — | — |
| etcd | `substrate-etcd` | ✓ | — | — | — |
| RabbitMQ | `substrate-rabbitmq` | — | ✓ | — | ✓ |
| SNS/SQS | `substrate-sns` | — | — | — | ✓ |
| In-memory | built into `substrate-core` | ✓ | ✓ | ✓ | ✓ |

Backends differ in TTL precision and in how much of the Journal lifecycle they enforce;
[Choosing a Backend](https://jwcarman.github.io/substrate/backends/) and each backend's page
explain.

## More

- [Documentation](https://jwcarman.github.io/substrate/) — concepts, each primitive, every
  backend, encryption at rest, testing and operating.
- [API reference](https://jwcarman.github.io/substrate/api/latest/)
- [Changelog](CHANGELOG.md)

## License

[Apache License 2.0](LICENSE)
