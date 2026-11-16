# NEXORA — Nukkit-MOT server migration plan

## Approved product decision

As of 2026-11-16, the project direction is to build and validate the NEXORA server pack on Nukkit-MOT first. Do not begin the Android launcher or custom client implementation until the MOT-based server has successfully started on the user's McpeHost hosting and a retail Minecraft Bedrock client has joined and completed basic gameplay tests.

The user reports that, on the same device, Minecraft version and hosting account, Nukkit-MOT appears to accept the connection while PowerNukkitX produces a Terracotta connection error. Treat this as a useful observation, not yet as proof of a specific protocol defect.

## Repository safety

- Work only on `nexora/compatibility-edition`.
- Never modify `main`.
- Do not delete existing NEXORA runtime, Android client, identity, or world data as part of the migration.
- Keep the current standalone runtime as a preserved experimental codebase until a deliberate migration or archival decision is made.
- Do not claim that a MOT build exists or is tested until an actual build and host/client test have succeeded.

## Important current repository mismatch

The current `pom.xml` describes a standalone NEXORA runtime, not a Nukkit plugin/server pack. It currently targets Java 25. This conflicts with the agreed OpenJDK 21 target and the newly approved Nukkit-MOT foundation. The current README also describes a custom Bedrock runtime and experimental NetherNet ingress. These are not automatically compatible with Nukkit-MOT.

Therefore, do not simply change the compiler version or add MOT dependencies to the existing runtime. First inspect the repository modules and the exact Nukkit-MOT release/API selected for the host, then create a separate MOT integration module or dedicated plugin project with its own build configuration.

## Delivery stages

### Stage 1 — Establish a reproducible Nukkit-MOT baseline

- Identify the exact Nukkit-MOT build/version and supported Java runtime.
- Build or obtain the matching server JAR from its official source.
- Run it locally or in a safe test slot without overwriting the current server data.
- Confirm retail Bedrock login, lobby spawn, movement, chat, reconnect and graceful restart.
- Record the exact host port requirements and startup command.

### Stage 2 — NEXORA server pack

Implement NEXORA as MOT-compatible plugins/modules, keeping server-authoritative gameplay:
- NEXORA Core integration and shared configuration.
- Lobby and navigation.
- Ranks, permissions, chat formatting and staff tools.
- Profiles, statistics, economy and cosmetics.
- RPG classes, abilities, items, mobs and bosses.
- Resource-pack distribution and client-visible presentation, within Bedrock/MOT support.

### Stage 3 — Stability and release test

- Verify clean startup and shutdown.
- Verify reconnects and two-player sessions.
- Verify each gameplay module independently.
- Test on the actual McpeHost plan with Bedrock 1.26.60.
- Keep backups and document rollback.

### Stage 4 — NEXORA Client and Android launcher

Only after Stage 3 succeeds:
- Define what can be delivered with a normal Bedrock resource pack versus what requires a custom client.
- Prototype client-only combat particles, hit/critical effects, animations and pulse visuals.
- Build the Android launcher for install/update/profile/server selection.
- Do not assume an ordinary launcher APK can modify the official Minecraft app. A custom Bedrock client requires a lawful, technically viable client implementation and separate compatibility testing.

## Acceptance gate

The first milestone is not a launcher or a feature-rich lobby. It is a MOT-based server that starts reliably on McpeHost and accepts an unmodified retail Bedrock client without Terracotta, then supports basic movement and chat.