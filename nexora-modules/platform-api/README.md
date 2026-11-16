# NEXORA Platform API

The first engine-independent contract module for the NEXORA server platform.

## Scope

- Module identity, API compatibility metadata and declared dependencies.
- Explicit module lifecycle hooks.
- A small host context with server information, typed services and logging.
- Java 17 source compatibility.

## Build

From this directory:

```sh
mvn test
```

## Current status

This is an isolated API module. It is intentionally not included in the repository's
root Maven build and does not import Nukkit-MOT classes. No Nukkit adapter, module
discovery/loading implementation or runtime wiring exists yet. Those pieces will be
added only after the Nukkit-MOT baseline is imported and reproducibly built.
