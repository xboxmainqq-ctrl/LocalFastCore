# NexoraCore — Compatibility Edition

NexoraCore is the first isolated foundation module for the NEXORA server project.

## Current stage

This is a Nukkit-MOT plugin built with Java 17 against Nukkit-MOT 1.26.60-R1. It is not yet a standalone server fork. Nukkit-MOT continues to load legacy plugins while NEXORA APIs are introduced additively.

## Included in 0.1.0-SNAPSHOT

- Plugin bootstrap and lifecycle handling.
- Typed, thread-safe service registry.
- Public `NexoraAPI` entry point for future modules.
- Synchronous typed event bus with removable subscriptions.
- Player join/quit lifecycle bridge that publishes UUID and current name without exposing mutable Nukkit player objects.
- Namespaced persistent key-value storage, saved atomically to the plugin data folder.
- Default configuration resource.
- JUnit tests for core utility APIs.

## Compatibility rules

- Existing plugins continue to be loaded by Nukkit-MOT.
- This module does not rewrite, rename, or modify existing plugin JARs.
- Keep the `LocalFastCore` plugin ID, commands, permissions, Java packages, and data paths unchanged while ClassAbilities depends on it.
- Compile success is not runtime compatibility. Test the exact server build and installed plugin set on a copy before deployment.

## Build

From the repository root:

    mvn -f nexora-core/pom.xml --batch-mode --no-transfer-progress clean verify

Output JAR:

    nexora-core/target/NexoraCore-0.1.0-SNAPSHOT.jar

## Next milestones

1. Verify this plugin on the target Nukkit-MOT server.
2. Add stable player profile APIs and persistence/migration rules.
3. Add protocol negotiation and client capability handshake.
4. Add NexoraUI abstractions with a fallback for ordinary Bedrock clients.
5. Expand automated compatibility checks for LocalFastCore and ClassAbilities.
6. Evaluate a maintained server fork only after the compatibility layer is stable.

Do not install this development snapshot on the live server. Back up the server and test on a copy first.

## Platform independence

The ru.nexora.core.platform package defines platform-neutral contracts. New public APIs should use NEXORA-owned immutable models such as PlayerSnapshot, never expose Nukkit classes or live platform objects. The current Nukkit-MOT bootstrap and listener bridge are still the host adapter; the new NexoraPlatform contract is not yet wired to a runtime implementation. See [NEXORA_STANDALONE_ARCHITECTURE.md](../NEXORA_STANDALONE_ARCHITECTURE.md) for the staged migration plan and removal criteria.
