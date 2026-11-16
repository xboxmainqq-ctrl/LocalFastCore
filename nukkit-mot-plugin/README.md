# NEXORA MOT Plugin

This is the isolated NEXORA integration module for **Nukkit-MOT 1.26.60-R1**.

## Requirements

- Java 21+
- Maven 3.9+
- Nukkit-MOT 1.26.60-R1 server

## Build

From this directory:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

The plugin JAR is written to `target/nexora-mot-plugin-0.1.0-SNAPSHOT.jar`.

Copy that JAR into the Nukkit-MOT server's `plugins/` directory and restart the server.

## Current status

The first milestone is deliberately small: a loadable plugin entry point that logs enable/disable events. It does not yet replace Nukkit-MOT's server core and does not yet contain NEXORA gameplay systems.

Next steps, after CI confirms compilation:

1. Verify plugin loading on the actual McpeHost Nukkit-MOT instance.
2. Add a NEXORA-owned configuration and lifecycle-managed service registry.
3. Add the lobby/profile/rank foundations.
4. Add the first complete RPG class loop only after real-server testing.

The existing standalone NEXORA runtime remains untouched and separate.
