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

- Single-chest gameplay foundation: protocol-2225 chest block/item state, validated chest placement and interaction, ContainerOpen/ContainerClose packets, 27-slot InventoryContent synchronization, active-window ownership checks, atomic player↔chest InventoryTransaction validation, and coordinate-keyed CRC32C persistence. Chest contents are never silently destroyed: breaking a non-empty chest is currently blocked until atomic item spill/drop is implemented. Double chests, trapped chests and other container types remain pending.

- Java 21 / Maven project.
- Protocol-2225 player chat: accepts TextPacket chat only from authenticated, initialized players over the reliable channel; checks any supplied XUID against the verified session, ignores the client-supplied display name, strips control characters, caps messages at 512 UTF-8 bytes and 5 messages per 5 seconds, and broadcasts using the authenticated gamertag. Outgoing chat XUID is left empty until player-list replication is implemented so clients do not hide messages from unknown senders. System and chat TextPackets use the protocol-2225 body variants.
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
- Protocol-2225 `ContainerOpen`/`ContainerClose` codecs and chest `InventoryContent` encoding, including the `LEVEL_ENTITY` full-container name; `BedrockSessionManager` opens single chests from validated block interactions, reconciles the player's and chest's inventories after every transaction, saves on each accepted transfer/close/disconnect, and closes windows on death. A non-empty chest cannot yet be broken because its full contents are not atomically spilled into item actors.
- Protocol-2225 biome registry captured from Bedrock Dedicated Server 1.26.60.6, stored with third-party attribution and sent during world bootstrap.
- Initial LevelChunk encoder: hashed block-state network IDs, version-9 sub-chunks, negative-height empty sections, 3D plains biome palettes and protocol-2225 chunk framing.
- Sends a bounded 3x3 starter chunk area around the player's current spawn position after StartGame and waits for the client's SetLocalPlayerAsInitialized acknowledgement before reporting PlayerSpawn.
- Bounded protocol-2225 PlayerAuthInput parser for movement snapshots; decodes per-tick input flags, block-action arrays, vehicle optional fields and the packed ItemUse transaction used for world interactions. ItemStackRequest remains explicitly opaque; normal UI inventory transactions are handled separately by the protocol-2225 InventoryTransaction codec.
- Server-side movement simulation is enabled in the production bootstrap. Protocol-2225 PlayerAuthInput move vectors and sprint/jump flags drive PlayerPhysics; the server owns position, velocity, gravity, jump impulse and block collision, compares each client prediction, and sends CorrectPlayerMovePrediction (0xA1) corrections with the input tick. StartGame advertises a 20-tick rewind history. The previous bounded client-position/token-bucket validator remains available as a compatibility path for tests and embedded integrations. Basic fall-distance tracking and server-side fall damage are integrated; sneak/swim/fly modifiers, knockback, effects and real-client tuning remain pending.
- Incremental 3x3 chunk-window streaming as a player crosses chunk boundaries; updates NetworkChunkPublisherUpdate and sends only chunks not already in the overlapping window.
- Starter block breaking and placement: parses protocol-2225 block actions and packed ItemUse transactions, checks authoritative player reach, target block runtime ID, clicked face, collision with the player and the exact held-item descriptor; consumes one registered stone/dirt/grass-block item only after inventory persistence succeeds, updates the world and broadcasts hashed-ID UpdateBlock packets. Unsupported block items and inventory actions are rejected without consuming items.
- Initial server-authoritative world model: 24 vertical sections (-64..319), sparse chunk sections, negative block coordinates, and a deterministic flat starter platform. Runtime block mutations publish ordered per-chunk change events for future gameplay modules; generation and storage loading do not emit runtime events.
- Versioned world-chunk persistence with CRC32C integrity checks and atomic file replacement; loaded chunks are restored at startup and saved on shutdown.
- Configurable periodic world and player autosave (default 300 seconds) with a final synchronous flush during graceful shutdown; set NEXORA_AUTOSAVE_SECONDS to change the interval.
- Player inventory persistence format v2 stores the 51-slot player UI crafting grid in the same checksummed, atomically replaced file as the main inventory; legacy v1 inventories still load. Uncrafted grid items are returned to the player inventory (or dropped if it is full) on disconnect and shutdown.
- Server-side crafting foundation: immutable shaped and shapeless recipe definitions, a duplicate-safe recipe registry, and an all-or-nothing crafting service that consumes ingredients across inventory slots, splits large outputs into valid stacks, preserves the selected hotbar slot and rolls back when ingredients or output space are insufficient. The initial verified recipes convert iron and gold ingots into nine corresponding nuggets and one oak log into four oak planks, and two vertically arranged oak planks into four sticks, and four oak planks in a 2x2 square into a crafting table. Protocol-2225 CraftingData is sent without clearing the client's vanilla recipe catalog, and CraftingEvent snapshots are validated against recipe UUID, the 2x2 inventory grid, exact output and server inventory before settlement. Shaped 3x3 crafting-table recipes, recipe discovery for the full vanilla catalog and real-client UI verification remain pending.
- Server-side inventory foundation: immutable namespaced item stacks, a synchronized 36-slot player inventory attached to each authenticated session, hotbar selection, deterministic stack merging, exact all-or-nothing item removal, atomic partial slot-to-slot transfers (compatible stacks only), safe slot swaps, and immutable inventory snapshots. Player inventories are persisted separately in versioned, CRC32C-checked files with atomic replacement and restored on authenticated login. Protocol-2225 InventoryContent and InventorySlot encoders serialize NetworkItemStackDescriptor values with the signed 16-bit Bedrock runtime item ID, stack count, aux value, explicit `hasStackNetId=false`, block runtime ID and user-data bytes. The immutable BedrockItemRegistry validates unique namespaced IDs and runtime IDs, and supplies strict forward/reverse item-definition lookup plus per-item stack limits. A protocol-2225 InventoryTransaction decoder now handles the normal transaction subset, including legacy request ID gating, current source-presence booleans, bounded action counts, stack descriptor validation and rejection of unsupported StackNetIds or unknown runtime IDs. BedrockSessionManager routes packet 0x1e through the decoder and atomic authoritative inventory validator, then sends InventoryContent plus MobEquipment to reconcile the client's prediction and selected hotbar item. Protocol-2225 MobEquipment is also decoded for the local player: entity ID, descriptor, hotbar slot and container are validated against the authoritative inventory before the selected slot is changed. Player container 0 and only the currently-open chest window (IDs 1..100) are accepted for cross-container transfers; stale snapshots, duplicate slot references, item creation/deletion, unknown item definitions, unsupported transaction variants and unrelated containers are rejected. Protocol-2225 ItemRegistryPacket (0xA2) is now emitted immediately after StartGame in the same Bedrock batch. Registry entries carry explicit runtime IDs, component flags, ItemVersion values and pre-encoded network-NBT component data; the encoder does not invent component tags. The production bootstrap installs a curated catalog of 96 verified protocol-2225 non-component vanilla items, including common stackables, armor and shield (ingots, gems, food and common materials); the catalog's runtime IDs and ItemVersion values are transcribed from the protocol-2225 `runtime_item_states_2225.json` palette in Nukkit-MOT revision `7323f582e864782934d4c405505551f79f4ed10e`. This is a deliberate supported subset, not a complete vanilla catalog. Component-based items, most block items, tools/weapons, custom magic items and resource-pack-backed visuals remain pending. The initial placeable block subset now includes stone, dirt, grass block, cobblestone, gravel, glass, obsidian, netherrack, end stone, coal/iron/gold/diamond/emerald blocks, plus a north-facing single chest, oak log, oak planks and crafting table with explicit protocol-2225 item and block runtime IDs. Initial inventory sync is sent as InventoryContent immediately after SetLocalPlayerAsInitialized when every stored item has an exact registered Bedrock definition; otherwise it is deferred rather than serializing guessed IDs. The server also sends the ItemRegistry packet immediately after StartGame; registry entries require exact runtime IDs and pre-encoded network-NBT component definitions. The selected held item is synchronized with MobEquipment after spawn and after inventory reconciliation. Player UI container slots beyond single chests, crafting-table interaction/3x3 grid, stack-wide drop controls and full client compatibility testing remain outstanding. The protocol-2225 PlayerAction DROP_ITEM action is decoded and drops exactly one item from the authoritative selected hotbar slot. Basic block placement through the packed PlayerAuthInput ItemUse transaction is supported.
- Player profiles keyed by verified XUID, stored in versioned CRC32C-protected files; position, rotation, health, hunger, saturation and exhaustion are restored on authenticated login and saved on disconnect, periodic autosave and shutdown. Version-1 profiles migrate to default survival vitals on load.
- Survival foundation: immutable health/hunger/saturation/exhaustion state; damage, healing, food restoration and saturation-first exhaustion; server-clock natural regeneration at hunger 18+ and starvation damage down to one health. PlayerPhysics accumulates fall distance and applies server-side fall damage when landing above the three-block safe threshold. Fatal damage sends DeathInfo and the SearchingForSpawn handshake; a validated PlayerAction respawn request resets the player to world spawn, restores default vitals and synchronizes a MovePlayer respawn position. Inventory is preserved on death until the dropped-item/death inventory rules are implemented. Food use supports a bounded set of vanilla foods: PlayerAuthInput starts use, an ItemRelease consume transaction validates the held stack and head position, and only then is one item consumed and hunger/saturation restored. CompletedUsingItem and inventory/vitals updates are sent to the client. Breaking supported blocks now creates persisted dropped-item actors; AddItemActor packets sync them to viewers of the chunk, TakeItemActor pickups are checked against server distance/ownership/delay and inventory capacity, and RemoveActor updates viewers after pickup. Protocol-2225 SetHealth and UpdateAttributes packets synchronize health and hunger-related attributes. Vitals are included in the versioned player profile with v1 migration.

## Not implemented / not wired yet

The following list is intentionally conservative: a protocol stage is only considered implemented after its packet format, state transitions and tests are present. A passing unit test is not a substitute for a real Android Bedrock connection test.

- The main bootstrap now starts the NetherNet HTTP signaling endpoint and the WebRTC answerer alongside the legacy UDP discovery experiment.
- NEXORA's signaling listener is plain HTTP; production TLS termination is provided by the documented Caddy reverse-proxy example. The application does not terminate TLS itself.
- Protocol-2225 `ContainerOpen`/`ContainerClose` and chest `InventoryContent` are integrated with live single-chest interaction. The session manager validates reach and target block state, opens a 27-slot window, routes only active-window inventory transactions, reconciles both inventories, and saves on transfer, close, death and disconnect. Non-empty chest breaking is intentionally blocked until item spills can be committed atomically; double/trapped chests remain pending.
- Chunk streaming currently uses a fixed 3x3 window; configurable view distance, explicit chunk unload handling, biome variation, item-actor bounce, death inventory drops, tool-aware hardness, more complex food effects, atomic non-empty chest spilling, double/trapped chests, shaped crafting-table recipes, sneak/swim/fly movement modifiers and player/mob entity replication are not implemented. Basic server-owned gravity, jumping, walking/sprinting and block collision are now integrated into the production PlayerAuthInput path, but movement constants and corrections still need validation with real Android/Windows Bedrock clients. Player profiles and survival vitals are persisted. The world is still not playable until the remaining gameplay paths and an Android-client end-to-end test are complete.
- Outbound gameplay support includes login/bootstrap packets, LevelChunk, UpdateBlock, PlayStatus, initial player inventory/equipment synchronization, the full supported-subset ItemRegistry packet, single-chest ContainerOpen/Close and content synchronization, plus protocol-2225 CraftingData/CraftingEvent for the current 2x2 recipe subset. Entity replication, crafting-table windows, most other interactive UI packets and full vanilla recipe coverage remain pending.
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


Dropped-item actors are now represented in the world item manager and synchronized with protocol-2225 AddItemActor/RemoveActor packets. Server-side inventory drops require an exact registered Bedrock item definition and persist the inventory change before broadcasting. Client TakeItemActor requests are validated against the authenticated player's runtime ID, owner lock, pickup delay, distance and available inventory capacity; the whole stack is accepted or nothing changes. Ground-item actors are persisted in checksummed world-item storage and restored on world startup; spawn, pickup and removal update durable snapshots. A protocol-2225 PlayerAction DROP_ITEM request now drops one item from the authenticated player's selected hotbar slot; the server selects the item and quantity, persists inventory before broadcasting AddItemActor, and reconciles inventory on failure. Ground-item gravity, compatible-stack merging within 0.75 blocks and ten-minute timed despawn are integrated into the live item-actor tick; stack-wide drop controls remain pending.


## Player movement simulation

The world module includes a deterministic server-side movement simulator (`PlayerPhysics`) with bounded tick duration, walking/sprinting, jump impulse, gravity, ground contact, axis-separated wall collision and safe spawn validation. The production Bedrock session manager now feeds PlayerAuthInput movement vectors and sprint/jump flags into that simulator, stores authoritative position/velocity, and emits tick-associated CorrectPlayerMovePrediction packets when client prediction diverges. StartGame provides a 20-tick correction rewind window. This is the first integrated movement pass; water, sneak/fly, knockback, effects, fall damage and Android/Windows client tuning remain follow-up work.

## Cluster transfer (experimental; controlled-cluster validation required)

The transfer ledger and canonical player checkpoints must use shared JDBC storage instead of per-host files for a cross-host transfer. Configure a unique node ID on every server process and configure both shared stores:

~~~sh
NEXORA_NODE_ID=survival-01
NEXORA_TRANSFER_JDBC_URL=jdbc:postgresql://db.example.internal:5432/nexora
NEXORA_TRANSFER_JDBC_USER=nexora
NEXORA_TRANSFER_JDBC_PASSWORD=replace-with-secret
NEXORA_TRANSFER_JDBC_INITIALIZE_SCHEMA=false
NEXORA_ONLINE_PLAYER_LEASE_MS=15000
NEXORA_PLAYER_STATE_JDBC_URL=jdbc:postgresql://db.example.internal:5432/nexora
NEXORA_PLAYER_STATE_JDBC_USER=nexora
NEXORA_PLAYER_STATE_JDBC_PASSWORD=replace-with-secret
NEXORA_PLAYER_STATE_JDBC_INITIALIZE_SCHEMA=false
~~~

- The first three JDBC environment variables in each group configure the corresponding store; credentials must be provided as a pair.
- Set the two `*_INITIALIZE_SCHEMA` flags to `true` only when intentionally allowing startup to create the tables. Prefer deployment-managed schema migrations in production.
- Startup validates connectivity and all three shared schemas (transfer ledger, online leases, canonical player checkpoints) before enabling the Bedrock listener, even when schema initialization is disabled. Missing migrations fail startup rather than surfacing on the first player login.
- The matching JDBC driver must be present on the runtime classpath. The player checkpoint schema initializer currently supports H2, PostgreSQL and MySQL/MariaDB.
- All nodes must read and write the same strongly consistent primary database for transfer decisions, checkpoints and online leases. Do not route ownership reads through asynchronous replicas. Online lease expiry uses the database `CURRENT_TIMESTAMP`; keep node clocks synchronized with NTP as transfer-record timestamps and handoff claims also use epoch milliseconds.
- When a shared transfer ledger is selected, startup refuses to continue unless player checkpoints are also configured as shared. This prevents a destination from claiming a transfer whose inventory/equipment/position snapshot exists only on another host's local disk.
- The same JDBC connection settings also create/use `nexora_player_online_leases`. A successful Bedrock login atomically acquires one XUID lease; active sessions renew it every third of `NEXORA_ONLINE_PLAYER_LEASE_MS` (default 15 seconds), and disconnect/transfer freeze releases it. A second node cannot admit the same XUID until the first lease is released or expires. A node that cannot renew is stopped fail-closed. Periodic autosave and disconnect persistence validate ownership before writing shared player state; autosave keeps the lease rather than releasing it.
- `NEXORA_TRANSFER_JDBC_INITIALIZE_SCHEMA=true` also initializes the online-lease table. Use the same shared SQL database on every node; local file-backed mode only protects duplicate sessions within one process.

For deployment-managed migrations, create the online lease table before starting nodes:
~~~sql
CREATE TABLE nexora_player_online_leases (
    player_xuid VARCHAR(64) PRIMARY KEY,
    node_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    lease_expires_at BIGINT NOT NULL
);
~~~

- Leave both JDBC URLs unset for the single-host file-backed development mode.

**Important:** shared SQL storage is only the persistence foundation. Authenticated TCP peer transport, destination endpoint and source coordinator are wired when cluster configuration is present; the operator console can request a transfer. The protocol-2225 TransferPacket redirect is guarded by a matching durable COMMITTED record. This remains an experimental controlled-cluster feature, not a production guarantee: real Bedrock client reconnect, actual multi-process kill/restart races, network partitions and production database failover still require end-to-end validation. A shared JDBC online-XUID lease now prevents duplicate admission even before the first transfer record; the source and destination both fail closed when they lose ownership. Do not enable it on public production servers until the remaining end-to-end checks are complete.

For an authenticated TCP cluster link, configure the cluster listener, each peer's cluster endpoint, each peer's Bedrock client route, and a distinct pairwise HMAC key for every peer:

~~~sh
NEXORA_CLUSTER_BIND=10.20.0.1:19133
NEXORA_CLUSTER_PEERS=survival-02=10.20.0.2:19133
NEXORA_CLUSTER_BEDROCK_ROUTES=survival-02=bedrock-02.example.net:19132
NEXORA_CLUSTER_PEER_KEYS=survival-02=BASE64_PAIRWISE_KEY
NEXORA_TRANSFER_LEASE_MS=15000
~~~

Generate at least 32 random bytes per node pair and Base64-encode them. Configure the same pairwise key under the opposite peer ID on both nodes; do not reuse one key across unrelated peers. HMAC authenticates messages but does not encrypt the TCP stream, so keep the cluster listener on a private network/VPN or behind an authenticated encrypted tunnel. The Bedrock route is the address the client will connect to after the target commits the transfer.

Once the cluster transport is active, the server process console exposes:
- `players` — list authenticated players with their XUID and entity ID
- `transfer <xuid> <target-node>` — request a transfer through the authenticated peer transport
- `transfer recover` — reconcile frozen source sessions from durable ABORTED/COMMITTED outcomes after a peer or database restart

The transfer command is operator-console only. A player is frozen before the transfer is published; the source resumes only after a durable abort, and redirects only after the target's durable commit. If the outcome cannot be confirmed, the source remains frozen for recovery rather than risking two active copies. In shared-ledger mode, login and active-session processing consult the latest per-XUID transfer decision, and every login prefers the shared canonical checkpoint even when no prior transfer record exists. Host-local profile/inventory files are only fallback migration sources when no canonical snapshot has yet been created. If a player has no canonical snapshot yet, the first successful Bedrock initialization writes the restored aggregate to shared storage before gameplay is admitted. Expired claims are durably revoked during startup recovery or login reconciliation.
