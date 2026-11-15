# Example flat-world generator

This is a separate JAR extension for the NEXORA Dedicated Server. It registers
the generator ID `example_flat` and creates a simple, deterministic grassy
world with bedrock, stone, dirt and grass layers.

## Build

Build and install the NEXORA runtime API into the local Maven repository first:

```bash
mvn --batch-mode --no-transfer-progress -DskipTests install
```

Then build this example JAR:

```bash
mvn --batch-mode --no-transfer-progress -f examples/generators/flat-generator/pom.xml package
```

The extension JAR is created at:

`examples/generators/flat-generator/target/nexora-flat-generator-1.0.0.jar`

## Install

Copy the JAR into:

`nexora-data/extensions/generators/`

Set the generator in `nexora-data/server.properties`:

```properties
world.generator=example_flat
```

Start the server normally. The extension is loaded at startup. New chunks use
the flat generator; already saved chunks remain unchanged.

Only install extensions you trust: generator JARs run as Java code inside the
server process and are not sandboxed.
