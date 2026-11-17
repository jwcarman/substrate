# Native Images

Substrate works in a GraalVM native image built with Spring Boot's AOT support. It ships the one
piece of metadata Spring cannot work out on its own: reflection hints for the record its notifier
sends between nodes. Its auto-configuration, properties and beans are handled by Spring AOT as
usual.

## What you register

Substrate cannot know the types you store. Register reflection hints for every payload type you
pass to an Atom, Journal or Mailbox, as you would for any type your codec serializes:

```java
@Configuration
@RegisterReflectionForBinding({Session.class, OrderEvent.class, Answer.class})
class NativeHints {}
```

A native image that starts, sends notifications, and then fails decoding one of your values is
missing exactly this.

## Backends

The hints were worked out against an application using the in-memory and Redis backends. Other
backends have not been exercised in a native image. One known gap: the Hazelcast backend sends
three classes between cluster members with Java serialization (`SetProcessor`,
`CompareAndSetProcessor` and `AtomEntry`), and no serialization hints are registered for them, so
the Hazelcast Atom does not work in a native image without adding them.

## Checking a build

Build the image and exercise one round trip per primitive you use:

```bash
mvn -Pnative spring-boot:build-image
```
