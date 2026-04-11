# Add `NatsAtomAutoConfigurationTest`

## What to build

`NatsAtomAutoConfiguration.java` sits at 0% coverage with 3
uncovered lines. Every other backend has a companion
`*AutoConfigurationTest` exercising its auto-wiring via Spring
Boot's `ApplicationContextRunner`. Nats's atom auto-config was
simply missed.

Write `NatsAtomAutoConfigurationTest` following the pattern
established by the other backends' auto-config tests.

## Pattern to follow

Look at any existing backend auto-config test for the shape, e.g.,
`substrate-nats/.../journal/NatsJournalAutoConfigurationTest.java`
(if it exists) or
`substrate-cassandra/.../atom/CassandraAtomAutoConfigurationTest.java`.

The typical shape:

```java
@Test
void createsAtomFactoryWhenConnectionBeanPresent() {
  new ApplicationContextRunner()
      .withUserConfiguration(MockNatsConnectionConfiguration.class)
      .withConfiguration(AutoConfigurations.of(NatsAtomAutoConfiguration.class))
      .run(context -> {
        assertThat(context).hasSingleBean(NatsAtomSpi.class);
        // and/or AtomFactory / AtomSpi depending on what the auto-config exposes
      });
}

@Test
void doesNotCreateAtomFactoryWhenDisabled() {
  new ApplicationContextRunner()
      .withPropertyValues("substrate.nats.atom.enabled=false")
      .withConfiguration(AutoConfigurations.of(NatsAtomAutoConfiguration.class))
      .run(context -> {
        assertThat(context).doesNotHaveBean(NatsAtomSpi.class);
      });
}
```

The exact assertions depend on what `NatsAtomAutoConfiguration`
actually wires. Read the class first and mirror the test of the
nearest sibling (`NatsJournalAutoConfigurationTest` is the best
reference — same module, same shape of auto-config).

## Acceptance criteria

- [ ] `substrate-nats/src/test/java/.../atom/NatsAtomAutoConfigurationTest.java`
      exists.
- [ ] The test covers at least the "auto-config creates its bean
      when prerequisites are met" happy path.
- [ ] The test covers the "auto-config stays quiet when
      `substrate.nats.atom.enabled=false`" (or equivalent
      conditional property) if such a conditional exists in the
      auto-config class. Match whatever conditionals the class
      actually has.
- [ ] The test uses `ApplicationContextRunner` and
      `AutoConfigurations.of(...)` — the same pattern as the
      other backends. Do NOT start a full Spring Boot context.
- [ ] If `NatsAtomAutoConfiguration` depends on an upstream bean
      (a `Connection`, `CredentialsProvider`, or similar), mock
      it via a nested `@TestConfiguration` class inside the test.
      Follow the pattern from `CassandraAutoConfigurationTest` or
      `MongoDbJournalAutoConfigurationTest`.
- [ ] `./mvnw -pl substrate-nats verify` passes.
- [ ] `NatsAtomAutoConfiguration.java` coverage > 90% on the
      next Sonar scan.

## Implementation notes

- Before writing the test, read `NatsAtomAutoConfiguration.java`
  and at least one peer auto-config test. The SPI-specific
  details (bean name, dependencies, conditional properties) vary
  module by module and you need to match this one.
- If `NatsAtomAutoConfiguration` has no conditional branches
  (no `@ConditionalOnProperty`), skip the "disabled" test — only
  test what the class actually does.
- Use the module's existing test dependencies. Do NOT add
  new deps to the module POM.
- Do NOT start any real NATS connection in the test. The whole
  point of `ApplicationContextRunner` is to exercise the
  auto-config logic without running the backend.
