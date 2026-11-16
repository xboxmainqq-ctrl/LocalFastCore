# NEXORA Platform API

The first engine-independent contract module for the NEXORA server platform.

## Scope

- Module identity, API compatibility metadata and declared dependencies.
- Explicit module lifecycle hooks.
- Deterministic dependency ordering and lifecycle coordination.
- Reverse-order shutdown and rollback of modules already enabled when startup fails.
- JAR discovery through `META-INF/nexora/module.properties`.
- API-version validation and per-module classloaders.
- A small host context with server information, typed services and logging.
- Java 17 source compatibility.

## Module JAR descriptor

Every module archive must contain `META-INF/nexora/module.properties`:

```properties
id=nexora.example
name=Example Module
version=1.0.0
apiVersion=0.1
dependencies=nexora.identity,nexora.permissions
entrypoint=dev.example.ExampleModule
```

The entrypoint must implement `NexoraModule` and expose a public no-argument
constructor. Its `descriptor()` value must match the archive metadata.

## Host integration outline

```java
ModuleJarLoader loader = new ModuleJarLoader("0.1");
ModuleJarLoader.LoadedModules loaded = loader.loadDirectory(Path.of("modules"));
loaded.registerWith(moduleManager);
moduleManager.loadAll();
moduleManager.enableAll();

// During orderly shutdown:
moduleManager.disableAll();
loaded.close();
```

Load only trusted module JARs: a Java classloader is not a security sandbox.
The host should close the loaded-module handle after disabling modules.

## Build

From this directory:

```sh
mvn test
```

## Current status

The API, lifecycle manager and JAR discovery are isolated from the repository's
root Maven build and do not import Nukkit-MOT classes. The loader accepts an
explicit directory and module instances; permission enforcement, sandboxing,
hot reload and Nukkit-MOT runtime wiring are not implemented yet. Those pieces
will be considered only after the pinned Nukkit-MOT baseline is imported and
builds reproducibly.
