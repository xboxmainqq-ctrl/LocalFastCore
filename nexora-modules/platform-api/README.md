# NEXORA Platform API

The first engine-independent contract module for the NEXORA server platform.

## Scope

- Module identity, API compatibility metadata and declared dependencies.
- Explicit module lifecycle hooks.
- Deterministic dependency ordering and lifecycle coordination.
- Reverse-order shutdown and rollback of modules already enabled when startup fails.
- A small host context with server information, typed services and logging.
- Java 17 source compatibility.

## Build

From this directory:

```sh
mvn test
```

## Current status

The API and lifecycle manager are isolated from the repository's root Maven build
and do not import Nukkit-MOT classes. The manager accepts module instances; jar
discovery, class loading, permissions and Nukkit-MOT runtime wiring are not
implemented yet. Those pieces will be added only after the pinned Nukkit-MOT
baseline is imported and builds reproducibly.
