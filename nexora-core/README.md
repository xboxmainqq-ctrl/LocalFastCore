# NexoraCore — Compatibility Edition

NexoraCore is the first isolated foundation module for the NEXORA server project.

## Current stage

This is not yet a standalone Minecraft server fork. It is a Nukkit-MOT extension built against Java 17 and Nukkit-MOT 1.26.60-R1. This is intentional: the existing Nukkit-MOT loader continues to load legacy plugins while Nexora APIs are introduced gradually.

## Compatibility approach

- Existing plugins continue to be loaded by Nukkit-MOT, not by a replacement loader.
- This module does not rewrite, rename, or modify existing plugin JARs.
- The module is isolated under nexora-core/; the existing LocalFastCore Maven project and its build files are untouched.
- Compatibility must be tested against the exact server build and installed plugin set before deployment. Plugins that depend on undocumented Nukkit internals may need individual fixes.

## Included in 0.1.0-SNAPSHOT

- Nukkit-MOT plugin bootstrap.
- Typed, thread-safe service registry for future Nexora modules.
- Separate Maven build; CI is configured in the repository workflow.

## Build

From the repository root:

    mvn -f nexora-core/pom.xml --batch-mode --no-transfer-progress clean verify

The output JAR is created at nexora-core/target/NexoraCore-0.1.0-SNAPSHOT.jar.

## Next milestones

1. Verify startup on the target Nukkit-MOT server.
2. Add stable player/profile and event APIs.
3. Add NexoraUI abstractions while retaining Nukkit form compatibility.
4. Add automated compatibility tests for the existing LocalFastCore and ClassAbilities plugins.
5. Only after the plugin/API layer is stable, evaluate a maintained Nukkit-MOT server fork.

Do not install this development snapshot on the live server. Keep a full server backup and test on a copy first.
