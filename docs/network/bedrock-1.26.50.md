# NEXORA Bedrock 1.26.50 network target

Status: protocol target decision and research baseline. This document does not claim that a working Bedrock transport or playable server exists.

## Target

- Minecraft Bedrock Edition: **1.26.50**.
- Bedrock protocol version: **2193**.
- Primary dedicated-server transport: **NetherNet**.
- RakNet: not the primary transport for this target. Any future RakNet support must be an explicitly scoped legacy adapter and must not leak into the protocol/gameplay layers.

The official 1.26.50 changelog states that NetherNet is the default transport used by dedicated servers. The official protocol changelog lists protocol 2193 for 1.26.50 and 1.26.51. The Mojang protocol reference page for 1.26.50 is currently labelled 1.26.50-beta.0 but documents protocol 2193; use the stable changelog as the version/protocol pin and treat the beta-labelled page as schema reference only until its metadata is reconciled.

## Source of truth

1. Mojang official Bedrock 1.26.50 changelog:
   https://www.minecraft.net/en-us/article/minecraft--bedrock-edition-26-50-changelog
   - Confirms NetherNet is the default transport for dedicated servers.
2. Mojang official protocol reference, 1.26.50:
   https://mojang.github.io/bedrock-protocol-docs/1.26.50/
   - Packet schemas, supporting types, primitives and protocol version 2193.
   - Page metadata currently says 1.26.50-beta.0; do not silently treat that label as stable release metadata.
3. Mojang official protocol changelog:
   https://mojang.github.io/bedrock-protocol-docs/changelog/
   - Records protocol 2193 for 1.26.50 and 1.26.51, and packet schema changes by release.

## Architecture rules

- Keep transport, Bedrock session/protocol, gameplay, world, and persistence as separate layers.
- The game runtime must depend on a transport-neutral connection contract, not on NetherNet implementation classes.
- The transport adapter owns connection establishment, reliability/ordering semantics exposed by its API, close/error propagation, and transport diagnostics.
- The Bedrock protocol layer owns packet framing/serialization, compression/encryption negotiation, login/session state, and packet-version selection.
- Do not assume NetherNet is interchangeable with generic WebRTC. Its exact signaling, channel setup, framing, and platform requirements must be established from authoritative implementation/specification evidence before implementation.
- Do not copy packet IDs or schemas from a different protocol version. Pin schema inputs to protocol 2193 and add version-specific tests.
- Keep RakNet-specific code out of shared gameplay and packet model classes.

## Verified facts vs open questions

### Verified

- 1.26.50 dedicated servers use NetherNet as their default transport, per Mojang's official release notes.
- The official protocol changelog assigns protocol 2193 to 1.26.50 and 1.26.51.
- The official 1.26.50 protocol reference enumerates packet schemas, including Login, NetworkSettings, StartGame, LevelChunk, PlayerAuthInput and related packet types.

### Open questions — must be answered before claiming transport support

- Exact NetherNet wire protocol and server-side implementation contract.
- Signaling/discovery and connection establishment requirements for a standalone third-party server.
- Data-channel configuration, message boundaries, reliability and ordering guarantees.
- How transport identity/session metadata is bound to Bedrock login and encryption.
- Android client compatibility and any platform-specific constraints.
- Whether Mojang publishes a stable normative NetherNet specification or whether a compatible implementation must be derived from a maintained reference implementation and tested against real clients.

No packet bytes, handshake messages, encryption behavior, or transport guarantees are to be invented to fill these gaps.

## Acceptance gates

A transport implementation is not considered supported until all of these are demonstrated against an unmodified Bedrock 1.26.50 client:

1. A standalone NEXORA process binds/listens using the required NetherNet mechanism.
2. A real client establishes a transport connection.
3. Bedrock protocol negotiation reaches the expected protocol 2193 path.
4. Login/authentication and encryption complete correctly.
5. The client receives the resource-pack/start-game flow and enters the world.
6. Chunk streaming, movement input, block updates, disconnect and reconnect work.
7. Repeated connection tests produce useful transport/session diagnostics.

Unit tests and protocol serialization tests are necessary but do not replace real-client integration tests.

## Version update policy

When targeting a newer Bedrock release:
1. Read its official release notes and protocol changelog.
2. Record the exact game version and protocol number.
3. Diff packet schemas and supporting types against the pinned version.
4. Add/update version-specific serialization tests.
5. Re-run transport and login integration tests against that exact client version.
6. Change the supported-version claim only after the acceptance gates pass.
