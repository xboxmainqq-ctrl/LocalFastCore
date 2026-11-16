# NEXORA Core

This directory is the beginning of the independent NEXORA Bedrock server implementation.

## Current status

- Project scaffold only.
- No Bedrock/RakNet listener is implemented yet.
- No claim of Minecraft client compatibility is made at this stage.
- The existing Android experiment is kept untouched.

## Initial engineering objective

Make a supported Minecraft Bedrock client connect to a small, server-authoritative NEXORA test server before implementing RPG gameplay.

## Planned milestones

1. Select and pin a Bedrock protocol version; document the matching client version.
2. Implement RakNet discovery, handshake, reliability and session lifecycle.
3. Implement the minimum Bedrock login/resource-pack negotiation and spawn flow.
4. Verify a real Android Bedrock client can connect and remain connected.
5. Add player movement and two-client visibility.
6. Add world persistence, inventory and gameplay modules.

## Architecture direction

- Java 21 for the first implementation, subject to measured performance.
- Keep transport/protocol, world simulation, persistence and gameplay modules separated.
- Keep the server authoritative: clients request actions; the server validates and applies them.
- Start with one inexpensive-hosting profile and explicit limits for players, chunks and entities.
- Treat Nukkit-MOT as a protocol/architecture reference only, not as the NEXORA implementation base.

## Build

Requires JDK 21 and Maven 3.9+.

```sh
mvn -f server/nexora-core/pom.xml verify
```
