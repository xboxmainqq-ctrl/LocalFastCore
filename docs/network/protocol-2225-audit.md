# NEXORA protocol 2225 audit — Bedrock 1.26.60

Status: initial schema audit. This is not a complete packet codec and does not claim successful client login.

## Pinned target

- Game version: 1.26.60 stable
- Protocol: 2225
- Official versioned reference: https://mojang.github.io/bedrock-protocol-docs/1.26.60/
- Stable protocol changelog: https://mojang.github.io/bedrock-protocol-docs/changelog/

The versioned reference page currently displays the metadata label `1.26.60-beta.0`, while the stable changelog records the stable 1.26.60 release on 2026-10-27 with protocol 2225. Use the stable changelog to pin the release and the versioned reference for schema inspection; keep this metadata discrepancy visible rather than silently conflating the labels.

## Initial high-impact packet findings

This is a targeted audit of packets relevant to login, world start, chunk delivery and movement. It is not a complete review of all 64 packet changes recorded for the release.

| Packet | ID | Finding for protocol 2225 | Engineering consequence |
|---|---:|---|---|
| LoginPacket | 1 | Client sends a big-endian int32 protocol value and a serialized connection request string. No stable schema change is recorded for this packet. | Keep protocol validation separate from parsing/authenticating the connection request. |
| StartGamePacket | 11 | Server-to-client world-start packet; current schema has 25 top-level fields. | Build serializer against the exact 1.26.60 schema; do not use an older field list. |
| AddPlayerPacket | 12 | Current schema includes Build Platform as int32 at field index 16. | Preserve the exact field order and primitive width. |
| LevelChunkPacket | 58 | 1.26.60 adds the boolean field `Is Client Biome Update`. | Chunk serializer must include the new field in the correct wire position. |
| PlayerAuthInputPacket | 144 | 1.26.60 changelog records 202 changes in this packet's schema/supporting types, including enum value changes. | Do not reuse older enum tables; generate/version the supporting enums from protocol 2225. |

## PlayerAuthInput supporting-type changes to preserve

The 1.26.60 stable changelog for PlayerAuthInput records, among other changes:

- `ClientPlayMode.ExitLevel` changes from 3 to 7; `NumModes` changes from 4 to 9.
- `ContainerEnumName` adds ReservedContainerA-D with values 67-70.
- Recipe and enchanting container enum values are reassigned, including RecipeFoodContainer 18 -> 64, RecipeBlocksContainer 19 -> 65 and RecipeFurnaceItemsContainer 20 -> 66.
- `PlayerAuthInputData` raw sneak flags and InternalUpdate are reassigned to values 62-65.
- `TextProcessingEventOrigin` changes its enum numbering, with unknown becoming -1 and ServerChatPublic becoming 0.

The list above is intentionally not exhaustive. The official packet page is the source of truth for the complete 202-change record.

## Implementation order

1. Add a version-pinned protocol schema layer for protocol 2225.
2. Implement bounded binary primitives (big-endian fixed-width integers, varints, strings, arrays, UUIDs and vectors) with unit tests.
3. Implement packet header/framing and compression negotiation only after verifying the exact 1.26.60 reference.
4. Implement the minimum serverbound login path and validate protocol 2225.
5. Implement serverbound movement parsing using the 1.26.60 PlayerAuthInput schema and enum values.
6. Implement StartGame and LevelChunk serialization, including the new biome-update boolean.
7. Add golden serialization tests sourced from documented field order and captured/verified client exchanges.
8. Run integration tests against an unmodified 1.26.60 stable client.

## Non-goals for this audit

- It does not implement Minecraft authentication, JWT validation, encryption, or resource-pack negotiation.
- It does not implement NetherNet signaling or WebRTC.
- It does not claim that the server is playable.
- It does not copy schemas from 1.26.50 or infer missing fields from other protocol versions.

## Official packet references

- Packet index and protocol metadata: https://mojang.github.io/bedrock-protocol-docs/1.26.60/
- LoginPacket: https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/login-packet/
- StartGamePacket: https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/start-game-packet/
- AddPlayerPacket: https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/add-player-packet/
- LevelChunkPacket: https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/level-chunk-packet/
- PlayerAuthInputPacket: https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/player-auth-input-packet/
