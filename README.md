# NEXORA — standalone block-world Core and dedicated server

**Current direction (2026-11-17):** NEXORA is an independent, general-purpose block-world engine and dedicated server. The active product does not include an Android client or a separate RPG server/product. Three AI capabilities are in scope: native Mob AI, an optional Core AI Assistant for operators, and an optional Moderation AI Assistant. AI mods are out of scope.

Historical Android/RPG source and design documents remain in the repository for reference only; they are not active requirements and are not built by the server workflow.

## Current priorities

1. Reliable server lifecycle, configuration and clean shutdown.
2. World, chunk and block persistence; deterministic generation and bounded asynchronous loading.
3. Entity simulation, player movement, collision and authoritative interactions.
4. Networking, authentication, abuse limits and safe server operations.
5. Versioned extension API and data-driven content.
6. Native Mob AI and optional operator/moderation assistants, integrated without making AI a server dependency.

See [project context](docs/PROJECT_CONTEXT.md), [Core development log](docs/CORE_DEVELOPMENT_LOG.md) and [AI systems roadmap](docs/NEXORA_AI_SYSTEMS_ROADMAP.md).

## AI implementation snapshot

These are initial foundations, not finished AI products:

- **Mob AI:** `MobAiController` is connected to authoritative entity ticks. It supports configured PASSIVE/NEUTRAL/HOSTILE profiles, nearby-player perception, line-of-sight checks, neutral provocation, collision-aware pursuit with bounded A* routing over loaded ground cells, cached waypoints, a 512-node per-search limit, a shared 4,096-node pathfinding budget per server tick and 100-tick last-known-target memory after line of sight is lost; attack intents are emitted only for currently visible targets. The existing deterministic passive wander behavior remains available. Combat/damage, vertical navigation, dynamic hazard handling and persisted profiles are not complete.
- **Core AI Assistant:** `AiToolRegistry` enforces per-tool permissions, explicit approval for mutating operations and audit events that omit arguments. `AiModelProvider` is an optional provider SPI with bounded prompts and sensitive-context filtering. No production provider or full operator conversation/tool-planning workflow is bundled.
- **Moderation AI Assistant:** bounded rate/repetition signals retain message fingerprints instead of raw chat. Optional model summaries receive only signal metadata, never raw chat or player IDs. Recommendations always require human review; no automatic punishment is performed.

The Core must continue operating when AI is disabled or no model provider is configured.

## Runtime foundations

- Java 25 Maven runtime with no Nukkit, Bukkit or Minecraft server API dependency.
- Explicit server lifecycle and runtime-module startup/shutdown ordering.
- Deterministic tick scheduler and dedicated game loop.
- World registry, sparse block/chunk storage, block states, biome and fluid data.
- Deterministic world generators and bounded asynchronous chunk generation.
- Game-thread chunk commit pipeline, request deduplication, cancellation and per-tick commit limits.
- Versioned `.nxw` world storage with checksums, validation and atomic replacement.
- Player inventory/vitals persistence foundations, block break/place services and collision/movement components.
- Experimental Bedrock protocol/network adapters. They are not proof of complete compatibility or production readiness.
- Versioned NEXORA protocol experiments and transport abstractions.

## World storage

The internal `.nxw` format is an original NEXORA development format. Version 3 stores world height, chunk coordinates, non-air block identifiers/properties, sparse biome sections, fluid cells and a checksum. Legacy versions 1 and 2 remain readable. Saves stage a temporary file and replace the target atomically where supported.

**This is not the Minecraft Bedrock LevelDB world format.** Bedrock world import/export would require a separate adapter.

## Run the dedicated server

Build and verify:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Launch:

```bash
java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar --help
java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar --data-dir ./nexora-data
```

On first launch, NEXORA creates `./nexora-data/server.properties`. The main settings include:

```properties
world.id=world
world.generator=overworld
world.seed=0
world.min-y=-64
world.max-y-exclusive=320
server.tick-rate=20
server.network.enabled=false
```

World data, configuration and server logs are stored in the selected data directory. The server saves loaded worlds during graceful shutdown.

## Extensions

A sample generator extension is available at [examples/generators/flat-generator](examples/generators/flat-generator/README.md).

Trusted generator JARs can be installed in `./nexora-data/extensions/generators/`. Each JAR provides a Java service descriptor at:

`META-INF/services/ru.nexora.runtime.WorldGeneratorExtension`

The provider implements `WorldGeneratorExtension`, returns a stable ID and creates a `ChunkGenerator`. Select the generator using `world.generator=<extension-id>`.

**Security:** extensions are trusted Java code, not sandboxed plugins. They run with the server process's operating-system permissions. Install only extensions from trusted sources.

## Verification status

The repository contains regression tests for configuration, world storage, chunk lifecycle, entities and the new AI foundations. GitHub Actions runs observed for recent commits have failed almost immediately with no job steps or logs exposed by the available GitHub connection. Therefore, **the latest full Maven build and tests are not confirmed passing**. Do not treat a commit or the existence of tests as proof of a successful build.

The server workflow publishes a standalone server artifact and test reports when verification succeeds: [NEXORA Server Runtime workflow](https://github.com/hostgtaa-dev/LocalFastCore/actions/workflows/nexora-server.yml).

## Scope exclusions

- No Android client or Android APK build pipeline.
- No separate RPG server/product or RPG gameplay roadmap.
- No AI mods.
- No Luanti integration.
- No copied Nukkit/PowerNukkitX or proprietary Minecraft implementation/assets.

The historical source folders are retained for now; they are not part of the active server build.
