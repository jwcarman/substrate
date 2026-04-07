# Remove substrate-jackson module

**Depends on: spec 011 (SPI refactor) must be completed first.**

## What to build

Remove the `substrate-jackson` module entirely. It was built on the String-based API
and will be replaced by Codec integration in the core (upcoming specs).

### What to remove

- Delete the entire `substrate-jackson/` directory
- Remove `substrate-jackson` from the parent POM `<modules>` list
- Remove `substrate-jackson` from `substrate-bom` dependency management

## Acceptance criteria

- [ ] `substrate-jackson/` directory deleted
- [ ] Parent POM no longer lists `substrate-jackson` as a module
- [ ] `substrate-bom` no longer lists `substrate-jackson`
- [ ] No references to `substrate-jackson` remain anywhere in the codebase
- [ ] Full build passes (`./mvnw verify`)

## Implementation notes

- This is a deletion-only spec. No new code.
- The module was a transitional solution that will be replaced by Codec integration.
- No external consumers exist — project has never been released.
