# NEXORA Core

Independent Minecraft Bedrock server implementation. The code is an early development scaffold, not yet a playable Bedrock server.

## Current transport direction: NetherNet

The initial target is modern Minecraft Bedrock clients. NetherNet is the required primary transport for current clients; RakNet is retained only as a legacy protocol experiment.

The modern connection path is:

1. Client calls GET /v1/join to verify NetherNet support and read server metadata.
2. Client sends its complete WebRTC SDP offer to POST /v1/join/{networkId}.
3. NEXORA validates the client's GameServerToken and detached DTLS-fingerprint JWS, then creates a WebRTC answer.
4. NEXORA signs its server identity assertion and returns the complete SDP answer.
5. Client and server establish ICE, DTLS and SCTP data channels.
6. NEXORA receives framed Bedrock game messages on the reliable and unreliable channels.

Official Mojang onboarding guide: https://mojang.github.io/bedrock-protocol-docs/guides/nether-net-onboarding-guide/

## Implemented

- Java 21 / Maven project.
- Legacy RakNet unconnected ping/pong codec and UDP discovery responder (for protocol study; not sufficient for modern clients).
- NetherNet server metadata model and JSON serialization.
- NetherNet HTTP signaling endpoint:
  - GET /v1/join returns server metadata.
  - POST /v1/join/{networkId} validates the SDP request and delegates negotiation.
  - Request size limit, content-type validation, bounded worker pool, and explicit error responses.
- WebRTC SDP answerer using webrtc-java, with full ICE gathering and peer-session limits.
- NetherNet identity implementation:
  - Verifies client GameServerToken RS256 signatures against a configurable Minecraft auth JWKS endpoint.
  - Checks the configured issuer and validates expiry/not-before when those claims are present; audience validation can be enabled through the identity-service constructor.
  - Verifies the client's ES384 detached DTLS-fingerprint assertion.
  - Creates a persistent P-384 server identity keypair, self-signed server JWT and signed fingerprint assertion.
- NetherNet message framing and reliable-channel fragmentation/reassembly with size limits.
- WebRTC DTLS provides transport encryption; the legacy Bedrock application-layer cipher is intentionally disabled on NetherNet.
- Bedrock packet batch codec: VarInt packet lengths and headers, protocol 2225 RequestNetworkSettings parsing, NetworkSettings response, raw-DEFLATE compression framing and uncompressed framing.
- Initial Bedrock session stage: validates the requested protocol, sends NetworkSettings, and switches to negotiated compression.
- Login stage: matches the Login GameServerToken to the verified NetherNet identity, verifies the client's ES384 client-data JWT, requires an authenticated Xbox Live identity, and handles the empty ClientToServerHandshake used with NetherNet's DTLS transport.
- Empty resource-pack exchange: sends LoginSuccess, ResourcePacksInfo and ResourcePackStack, then validates the client's completion response.
- Initial world bootstrap: sends a protocol-2225 StartGame packet with a unique player runtime ID, survival mode, Overworld spawn metadata, followed by SetTime, SetDifficulty, SetCommandsEnabled, SetSpawnPosition, SetPlayerGameType and NetworkChunkPublisherUpdate.
- Protocol-2225 biome registry captured from Bedrock Dedicated Server 1.26.60.6, stored with third-party attribution and sent during world bootstrap.
- Initial LevelChunk encoder: hashed block-state network IDs, version-9 sub-chunks, negative-height empty sections, 3D plains biome palettes and protocol-2225 chunk framing.
- Sends a bounded 3x3 starter chunk area around the player's current spawn position after StartGame and waits for the client's SetLocalPlayerAsInitialized acknowledgement before reporting PlayerSpawn.
- Bounded protocol-2225 PlayerAuthInput parser for movement snapshots; decodes per-tick input flags, block-action arrays, vehicle optional fields and the packed ItemUse transaction used for world interactions. ItemStackRequest remains explicitly opaque; normal UI inventory transactions are handled separately by the protocol-2225 InventoryTransaction codec.
- Server-side movement simulation is enabled in the production bootstrap. Protocol-2225 PlayerAuthInput move vectors and sprint/jump flags drive PlayerPhysics; the server owns position, velocity, gravity, jump impulse and block collision, compares each client prediction, and sends CorrectPlayerMovePrediction (0xA1) corrections with the input tick. StartGame advertises a 20-tick rewind history. The previous bounded client-position/token-bucket validator remains available as a compatibility path for tests and embedded integrations. Fall damage, sneak/swim/fly modifiers, knockback, effects and real-client tuning remain pending.
- Incremental 3x3 chunk-window streaming as a player crosses chunk boundaries; updates NetworkChunkPublisherUpdate and sends only chunks not already in the overlapping window.
- Starter block breaking and placement: parses protocol-2225 block actions and packed ItemUse transactions, checks authoritative player reach, target block runtime ID, clicked face, collision with the player and the exact held-item descriptor; consumes one registered stone/dirt/grass-block item only after inventory persistence succeeds, updates the world and broadcasts hashed-ID UpdateBlock packets. Unsupported block items and inventory actions are rejected without consuming items.
- Initial server-authoritative world model: 24 vertical sections (-64..319), sparse chunk sections, negative block coordinates, and a deterministic flat starter platform. Runtime block mutations publish ordered per-chunk change events for future gameplay modules; generation and storage loading do not emit runtime events.
- Versioned world-chunk persistence with CRC32C integrity checks and atomic file replacement; loaded chunks are restored at startup and saved on shutdown.
- Configurable periodic world and player autosave (default 300 seconds) with a final synchronous flush during graceful shutdown; set NEXORA_AUTOSAVE_SECONDS to change the interval.
- Server-side inventory foundation: immutable namespaced item stacks, a synchronized 36-slot player inventory attached to each authenticated session, hotbar selection, deterministic stack merging, exact all-or-nothing item removal, atomic partial slot-to-slot transfers (compatible stacks only), safe slot swaps, and immutable inventory snapshots. Player inventories are persisted separately in versioned, CRC32C-checked files with atomic replacement and restored on authenticated login. Protocol-2225 InventoryContent and InventorySlot encoders serialize NetworkItemStackDescriptor values with the signed 16-bit Bedrock runtime item ID, stack count, aux value, explicit `hasStackNetId=false`, block runtime ID and user-data bytes. The immutable BedrockItemRegistry validates unique namespaced IDs and runtime IDs, and supplies strict forward/reverse item-definition lookup plus per-item stack limits. A protocol-2225 InventoryTransaction decoder now handles the normal transaction subset, including legacy request ID gating, current source-presence booleans, bounded action counts, stack descriptor validation and rejection of unsupported StackNetIds or unknown runtime IDs. BedrockSessionManager routes packet 0x1e through the decoder and atomic authoritative inventory validator, then sends InventoryContent plus MobEquipment to reconcile the client's prediction and selected hotbar item. Protocol-2225 MobEquipment is also decoded for the local player: entity ID, descriptor, hotbar slot and container are validated against the authoritative inventory before the selected slot is changed. Only player container 0 and transfer-only operations are accepted; stale snapshots, duplicate slot references, item creation/deletion, unknown item definitions, unsupported transaction variants and other containers are rejected. Protocol-2225 ItemRegistryPacket (0xA2) is now emitted immediately after StartGame in the same Bedrock batch. Registry entries carry explicit runtime IDs, component flags, ItemVersion values and pre-encoded network-NBT component data; the encoder does not invent component tags. The production bootstrap installs a curated catalog of 92 verified protocol-2225 non-component vanilla items, including common stackables, armor and shield (ingots, gems, food and common materials); the catalog's runtime IDs and ItemVersion values are transcribed from the protocol-2225 `runtime_item_states_2225.json` palette in Nukkit-MOT revision `7323f582e864782934d4c405505551f79f4ed10e`. This is a deliberate supported subset, not a complete vanilla catalog. Component-based items, most block items, tools/weapons, custom magic items and resource-pack-backed visuals remain pending. The initial placeable block subset is stone, dirt and grass block with verified protocol-2225 item and block runtime IDs. Initial inventory sync is sent as InventoryContent immediately after SetLocalPlayerAsInitialized when every stored item has an exact registered Bedrock definition; otherwise it is deferred rather than serializing guessed IDs. The server also sends the ItemRegistry packet immediately after StartGame; registry entries require exact runtime IDs and pre-encoded network-NBT component definitions. The selected held item is synchronized with MobEquipment after spawn and after inventory reconciliation. Player UI container slots, crafting, food/utility item use, client-originated drops and full client compatibility testing remain outstanding. Basic block placement through the packed PlayerAuthInput ItemUse transaction is supported.
- Player profiles keyed by verified XUID, stored in versioned CRC32C-protected files; position, rotation, health, hunger, saturation and exhaustion are restored on authenticated login and saved on disconnect, periodic autosave and shutdown. Version-1 profiles migrate to default survival vitals on load.
- Survival foundation: immutable health/hunger/saturation/exhaustion state; damage, healing, food restoration and saturation-first exhaustion; server-clock natural regeneration at hunger 18+ and starvation damage down to one health. Protocol-2225 SetHealth and UpdateAttributes packets synchronize health and hunger-related attributes. Vitals are included in the versioned player profile with v1 migration.

## Not implemented / not wired yet

The following list is intentionally conservative: a protocol stage is only considered implemented after its packet format, state transitions and tests are present. A passing unit test is not a substitute for a real Android Bedrock connection test.

- The main bootstrap now starts the NetherNet HTTP signaling endpoint and the WebRTC answerer alongside the legacy UDP discovery experiment.
- NEXORA's signaling listener is plain HTTP; production TLS termination is provided by the documented Caddy reverse-proxy example. The application does not terminate TLS itself.
- Chunk streaming currently uses a fixed 3x3 window; configurable view distance, explicit chunk unload handling, biome variation, additional placeable block types, tool-aware hardness, fall damage, death/respawn, food item consumption, sneak/swim/fly movement modifiers and entity replication are not implemented. Basic server-owned gravity, jumping, walking/sprinting and block collision are now integrated into the production PlayerAuthInput path, but movement constants and corrections still need validation with real Android/Windows Bedrock clients. Player profiles and survival vitals are persisted. The world is still not playable until the remaining gameplay paths and an Android-client end-to-end test are complete.
- Outbound gameplay support currently includes login/bootstrap packets, LevelChunk, UpdateBlock, PlayStatus and the limited InventoryContent reconciliation path for accepted or rejected normal player-inventory transactions. Initial inventory synchronization, player UI container slots, full item registry distribution, entity replication, chat and interactive UI packets remain unimplemented.
- Guest/offline authentication is not supported; the current login path requires an authenticated Xbox Live GameServerToken.
- No Android-client end-to-end connection test.

## Protocol target

Initial stable game target: **Bedrock 1.26.62 / game protocol 2225**. The transport target is NetherNet. Re-check Mojang's official protocol reference before changing either value.

## Legacy RakNet discovery experiment

The bootstrap runs the modern NetherNet signaling endpoint over TCP and keeps the legacy RakNet discovery responder over UDP on the same port. The transport path can establish WebRTC data channels, but it is not yet a playable server.

Build and test locally (the GitHub Actions workflow also runs this command for changes under server/nexora-core):

~~~sh
mvn -f server/nexora-core/pom.xml clean verify
~~~

The build copies runtime dependencies to server/nexora-core/target/lib/ so the application jar can be launched with its manifest classpath:

~~~sh
java -jar server/nexora-core/target/nexora-core-0.1.0-SNAPSHOT.jar 0.0.0.0 19132 "NEXORA Core" 20
~~~

The WebRTC implementation uses native libraries supplied by webrtc-java; the deployment host must match a published native classifier and have the required system runtime libraries installed. If the VPS is behind NAT, set NEXORA_STUN_URLS to a semicolon-separated list of STUN URLs so the answer can include a public server-reflexive ICE candidate. Leave it empty on a directly reachable public host; each configured STUN server adds connection-setup latency.

## HTTPS and firewall deployment

For production, prefer HTTPS signaling. A reverse proxy can terminate TLS while NEXORA serves HTTP on loopback:

~~~sh
NEXORA_HTTP_BIND=127.0.0.1
NEXORA_UDP_PORT_MIN=30000
NEXORA_UDP_PORT_MAX=30199
~~~

Copy Caddyfile.example to your Caddy configuration and replace example.com with a domain whose DNS points to the VPS. Caddy obtains and renews the TLS certificate automatically.

Open the following ports in the VPS firewall:

- TCP 443 for HTTPS signaling through Caddy (or TCP 19132 for direct HTTP signaling).
- UDP 30000-30199 for WebRTC ICE/DTLS/SCTP traffic.
- UDP 19132 only for the legacy RakNet discovery experiment.

The WebRTC UDP range is configurable with NEXORA_UDP_PORT_MIN and NEXORA_UDP_PORT_MAX. World and player autosave are configurable with NEXORA_AUTOSAVE_SECONDS (default 300 seconds). Player profiles are stored under data/players/. Set both UDP port bounds to 0 to use the operating system's default ephemeral range. The HTTP listener bind address is configurable with NEXORA_HTTP_BIND; it defaults to the main bind host.

## Identity key safety

The first initialization of DefaultNetherNetIdentityService creates:

- nethernet-identity.pk8 — private P-384 key; keep it secret and back it up securely.
- nethernet-identity.x509 — matching public key.

Keep both files together and persistent across restarts and server migrations. By default they live in data/identity; set NEXORA_DATA_DIR to a persistent data-volume path to relocate them. If one is missing or the keypair does not match, startup fails rather than silently rotating the server identity.

## Engineering rules

- Keep transport/protocol, world simulation, persistence and gameplay modules separated.
- Keep the server authoritative: clients request actions; the server validates and applies them.
- Start with one inexpensive-hosting profile and explicit limits for players, chunks and entities.
- Treat Nukkit-MOT as a protocol/architecture reference only, not as the NEXORA implementation base.
- Preserve applicable third-party license notices and obligations when studying or reusing external code.


Player equipment is maintained separately from the 36-slot main inventory. The server persists four armor slots, one offhand slot, the UI cursor and seven magical slots in checksummed `.nxe` files under `data/equipment`. After SetLocalPlayerAsInitialized, protocol-2225 InventoryContent packets synchronize the main inventory, armor (container 120) and offhand (container 119), while InventorySlot synchronizes the cursor (UI container 124). Main-inventory, armor, offhand and cursor transfers are validated server-side with item conservation and stale-slot checks; armor compatibility is defined by the server-owned vanilla armor catalog. Rejected transactions restore all inventory/equipment views from server state.

Players can open the Bedrock modal-form equipment interface with `/equipment` (or `/equip`). The server advertises `/equipment` through AvailableCommands and handles the authenticated CommandRequest; the form lists seven magical slots, shows current contents and bonuses, offers only registered compatible inventory items, and supports safe unequipping. Equip/unequip operations are persisted before updated inventory state is sent to the client. The initial catalog includes vanilla placeholders (blaze rod, gold nugget, nautilus shell, amethyst shard, heart of the sea and ghast tear) so the full flow can be exercised before custom NEXORA resource-pack items are introduced. Server-owned item definitions provide additive attributes such as health, armor, attack damage, movement, mana, magic power, cooldown reduction and elemental resistances; the session manager calculates the aggregate across armor, offhand and magical slots for future combat and spell systems. Custom item visuals, runtime definitions and a real Bedrock-client UI compatibility test are still outstanding.


Dropped-item actors are now represented in the world item manager and synchronized with protocol-2225 AddItemActor/RemoveActor packets. Server-side inventory drops require an exact registered Bedrock item definition and persist the inventory change before broadcasting. Client TakeItemActor requests are validated against the authenticated player's runtime ID, owner lock, pickup delay, distance and available inventory capacity; the whole stack is accepted or nothing changes. Ground-item actors are persisted in checksummed world-item storage and restored on world startup; spawn, pickup and removal update durable snapshots. Ground-item gravity, merging, despawn rules and client-originated drop-action decoding are still pending.


## Player movement simulation

The world module includes a deterministic server-side movement simulator (`PlayerPhysics`) with bounded tick duration, walking/sprinting, jump impulse, gravity, ground contact, axis-separated wall collision and safe spawn validation. The production Bedrock session manager now feeds PlayerAuthInput movement vectors and sprint/jump flags into that simulator, stores authoritative position/velocity, and emits tick-associated CorrectPlayerMovePrediction packets when client prediction diverges. StartGame provides a 20-tick correction rewind window. This is the first integrated movement pass; water, sneak/fly, knockback, effects, fall damage and Android/Windows client tuning remain follow-up work.
