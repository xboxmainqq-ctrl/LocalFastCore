# NEXORA Core

Independent Minecraft Bedrock server implementation. The current code is an early development scaffold, not yet a playable Bedrock server.

## Current milestone: RakNet discovery

Implemented:
- Java 21 / Maven project.
- RakNet offline Unconnected Ping (0x01) validation.
- RakNet Unconnected Pong (0x1c) encoding with Bedrock server-list advertisement.
- UDP discovery responder.
- Unit tests for packet validation and pong encoding.
- Target advertisement: Minecraft Bedrock 1.26.62, network protocol 2225.

Not implemented yet:
- RakNet connection handshake and reliable ordered frames.
- Bedrock login, encryption, compression and packet batches.
- Resource-pack negotiation, player spawn or gameplay.
- World, entities, inventory or persistence.

The responder deliberately handles discovery pings only. It does not claim to accept game connections and does not answer the "unconnected ping open connections" query (0x02) until connection handling exists.

## Target protocol

Initial stable target: **Bedrock 1.26.62 / protocol 2225** (as of 2026-11-16). Keep the protocol version and advertised game version together. Re-check Mojang's official protocol changelog before changing the target.

## Build

Requires JDK 21 and Maven 3.9+.

```sh
mvn -f server/nexora-core/pom.xml clean verify
```

## Run discovery responder

After building:

```sh
java -jar server/nexora-core/target/nexora-core-0.1.0-SNAPSHOT.jar 0.0.0.0 19132 "NEXORA Core" 20
```

Open UDP port 19132 in the host firewall. The process should appear in a Bedrock server list if the client and network can reach it. This is a discovery test only; selecting the entry will not yet complete a login.

## Engineering rules

- Keep transport/protocol, world simulation, persistence and gameplay modules separated.
- Keep the server authoritative: clients request actions; the server validates and applies them.
- Start with one inexpensive-hosting profile and explicit limits for players, chunks and entities.
- Treat Nukkit-MOT as a protocol/architecture reference only, not as the NEXORA implementation base.
- Preserve applicable third-party license notices and obligations when studying or reusing external code.
