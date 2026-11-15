package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/** Negotiates the private NEXORA protocol and sends initial world metadata. */
public final class NexoraPrivateProtocolHandler implements Consumer<TransportConnection> {
    private static final int REJECT_UNSUPPORTED_VERSION = 1;
    private static final int REJECT_INVALID_HELLO = 2;
    private static final int REJECT_UNSUPPORTED_CLIENT = 3;
    private static final String SUPPORTED_CLIENT_BUILD_PREFIX = "android-";
    private static final double PLAYER_VISIBILITY_RADIUS = 128.0;

    private final UUID serverId;
    private final int maxMessageBytes;
    private final Supplier<NexoraWorldInfo> worldInfoSupplier;
    private final Consumer<TransportConnection> readyListener;
    private final Supplier<World> worldSupplier;
    private final Function<ChunkPosition, Boolean> chunkLoader;
    private final AsyncChunkLoader asyncChunkLoader;
    private final Supplier<PlayerChunkStreamingManager> chunkStreamingManagerSupplier;
    private final Map<TransportConnection, PlayerSession> playerSessions =
            new ConcurrentHashMap<>();
    private final EntityRegistry entities;
    private final Map<UUID, PlayerInventory> inventories;
    private final FilePlayerInventoryStorage inventoryStorage;
    private final List<BedrockResourcePackTransferCodec.Pack> resourcePacks;

    /** Returns the authoritative entity registry for this server world. */
    public EntityRegistry entities() {
        return entities;
    }

    /** Returns the server-side inventory for an active player session. */
    public java.util.Optional<PlayerInventory> inventory(UUID playerId) {
        return java.util.Optional.ofNullable(inventories.get(Objects.requireNonNull(playerId, "playerId")));
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Consumer<TransportConnection> readyListener) {
        this(serverId, maxMessageBytes, () -> null, null, null, readyListener,
                new EntityRegistry(), new ConcurrentHashMap<>());
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Consumer<TransportConnection> readyListener) {
        this(serverId, maxMessageBytes, worldInfoSupplier, null, null, readyListener,
                new EntityRegistry(), new ConcurrentHashMap<>());
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Consumer<TransportConnection> readyListener) {
        this(serverId, maxMessageBytes, worldInfoSupplier, worldSupplier, null, readyListener,
                new EntityRegistry(), new ConcurrentHashMap<>());
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Function<ChunkPosition, Boolean> chunkLoader,
                                        Consumer<TransportConnection> readyListener) {
        this(serverId, maxMessageBytes, worldInfoSupplier, worldSupplier, chunkLoader,
                readyListener, new EntityRegistry(), new ConcurrentHashMap<>());
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Function<ChunkPosition, Boolean> chunkLoader,
                                        Consumer<TransportConnection> readyListener,
                                        EntityRegistry entities,
                                        Map<UUID, PlayerInventory> inventories) {
        this(serverId, maxMessageBytes, worldInfoSupplier, worldSupplier, chunkLoader,
                readyListener, entities, inventories, null);
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Function<ChunkPosition, Boolean> chunkLoader,
                                        Consumer<TransportConnection> readyListener,
                                        EntityRegistry entities,
                                        Map<UUID, PlayerInventory> inventories,
                                        FilePlayerInventoryStorage inventoryStorage) {
        this(serverId, maxMessageBytes, worldInfoSupplier, worldSupplier, chunkLoader,
                readyListener, entities, inventories, inventoryStorage, List.of());
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Function<ChunkPosition, Boolean> chunkLoader,
                                        Consumer<TransportConnection> readyListener,
                                        EntityRegistry entities,
                                        Map<UUID, PlayerInventory> inventories,
                                        FilePlayerInventoryStorage inventoryStorage,
                                        List<BedrockResourcePackTransferCodec.Pack> resourcePacks) {
        this(serverId, maxMessageBytes, worldInfoSupplier, worldSupplier, chunkLoader,
                readyListener, entities, inventories, inventoryStorage, resourcePacks, null);
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Function<ChunkPosition, Boolean> chunkLoader,
                                        Consumer<TransportConnection> readyListener,
                                        EntityRegistry entities,
                                        Map<UUID, PlayerInventory> inventories,
                                        FilePlayerInventoryStorage inventoryStorage,
                                        List<BedrockResourcePackTransferCodec.Pack> resourcePacks,
                                        AsyncChunkLoader asyncChunkLoader) {
        this(serverId, maxMessageBytes, worldInfoSupplier, worldSupplier, chunkLoader,
                readyListener, entities, inventories, inventoryStorage, resourcePacks,
                asyncChunkLoader, null);
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Function<ChunkPosition, Boolean> chunkLoader,
                                        Consumer<TransportConnection> readyListener,
                                        EntityRegistry entities,
                                        Map<UUID, PlayerInventory> inventories,
                                        FilePlayerInventoryStorage inventoryStorage,
                                        List<BedrockResourcePackTransferCodec.Pack> resourcePacks,
                                        AsyncChunkLoader asyncChunkLoader,
                                        PlayerChunkStreamingManager chunkStreamingManager) {
        this(serverId, maxMessageBytes, worldInfoSupplier, worldSupplier, chunkLoader,
                readyListener, entities, inventories, inventoryStorage, resourcePacks,
                asyncChunkLoader, () -> chunkStreamingManager);
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Function<ChunkPosition, Boolean> chunkLoader,
                                        Consumer<TransportConnection> readyListener,
                                        EntityRegistry entities,
                                        Map<UUID, PlayerInventory> inventories,
                                        FilePlayerInventoryStorage inventoryStorage,
                                        List<BedrockResourcePackTransferCodec.Pack> resourcePacks,
                                        AsyncChunkLoader asyncChunkLoader,
                                        Supplier<PlayerChunkStreamingManager> chunkStreamingManagerSupplier) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        if (maxMessageBytes < 1 || maxMessageBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("maxMessageBytes is outside the supported range");
        }
        this.maxMessageBytes = maxMessageBytes;
        this.worldInfoSupplier = Objects.requireNonNull(worldInfoSupplier, "worldInfoSupplier");
        this.worldSupplier = worldSupplier;
        this.chunkLoader = chunkLoader;
        this.asyncChunkLoader = asyncChunkLoader;
        this.chunkStreamingManagerSupplier = Objects.requireNonNull(chunkStreamingManagerSupplier, "chunkStreamingManagerSupplier");
        this.readyListener = Objects.requireNonNull(readyListener, "readyListener");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.inventoryStorage = inventoryStorage;
        Objects.requireNonNull(resourcePacks, "resourcePacks");
        if (resourcePacks.size() > 1) {
            throw new IllegalArgumentException("The Android client currently supports one resource pack");
        }
        if (!resourcePacks.isEmpty() && (worldSupplier == null
                || (chunkLoader == null && asyncChunkLoader == null))) {
            throw new IllegalArgumentException(
                    "Server resource packs require an active world and chunk loader");
        }
        this.resourcePacks = List.copyOf(resourcePacks);
    }

    @Override
    public void accept(TransportConnection connection) {
        Objects.requireNonNull(connection, "connection");
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<PlayerSession> activeSession = new AtomicReference<>();
        connection.setCloseListener(event -> {
            synchronized (connection) {
                completed.set(true);
                PlayerSession session = activeSession.getAndSet(null);
                if (session != null && playerSessions.remove(connection, session)) {
                    entities.remove(session.playerId);
                    PlayerChunkStreamingManager streaming = chunkStreamingManagerSupplier.get();
                    if (streaming != null) streaming.removePlayer(session.playerId);
                    boolean persisted = persistInventory(session.inventory);
                    if (persisted || inventoryStorage == null) {
                        inventories.remove(session.playerId, session.inventory);
                    }
                    broadcastPlayerLeft(connection, session.playerId);
                }
            }
        });
        connection.setMessageListener(message -> {
            if (completed.get() || !completed.compareAndSet(false, true)) {
                connection.close();
                return;
            }
            if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                    || message.sizeBytes() > NexoraPrivateProtocolCodec.MAX_MESSAGE_BYTES) {
                connection.close();
                return;
            }

            final NexoraPrivateProtocolCodec.ClientHello hello;
            try {
                hello = NexoraPrivateProtocolCodec.decodeClientHello(message.payload());
            } catch (RuntimeException malformed) {
                reject(connection, REJECT_INVALID_HELLO, "Invalid NEXORA client hello");
                return;
            }
            if (hello.majorVersion() != NexoraPrivateProtocolCodec.MAJOR_VERSION
                    || hello.minorVersion() != NexoraPrivateProtocolCodec.MINOR_VERSION) {
                reject(connection, REJECT_UNSUPPORTED_VERSION,
                        "Unsupported NEXORA protocol version");
                return;
            }

            // Bedrock/vanilla clients do not implement the private NEXORA hello.
            // Require the first-party Android build namespace before admitting a session.
            if (!hello.clientBuild().startsWith(SUPPORTED_CLIENT_BUILD_PREFIX)) {
                reject(connection, REJECT_UNSUPPORTED_CLIENT,
                        "This server requires the NEXORA client");
                return;
            }

            final NexoraWorldInfo worldInfo;
            try {
                worldInfo = worldInfoSupplier.get();
            } catch (RuntimeException unavailableWorld) {
                connection.close();
                return;
            }
            if (send(connection, NexoraPrivateProtocolCodec.encodeServerHello(
                    serverId, maxMessageBytes)) != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            if (worldInfo != null && send(connection,
                    NexoraPrivateProtocolCodec.encodeWorldInfo(worldInfo))
                    != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            if (worldInfo != null && worldSupplier != null && (chunkLoader != null || asyncChunkLoader != null) && send(connection,
                    NexoraMovementPacketCodec.encodeServerPosition(
                            new NexoraMovementValidator.Position(
                                    worldInfo.spawnX() + 0.5,
                                    worldInfo.spawnY(),
                                    worldInfo.spawnZ() + 0.5)))
                    != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            if (worldSupplier == null || (chunkLoader == null && asyncChunkLoader == null) || worldInfo == null) {
                connection.setMessageListener(ignored -> connection.close());
            } else {
                java.util.Set<ChunkPosition> requestedChunks = ConcurrentHashMap.newKeySet();
                AtomicBoolean resourcePackReady = new AtomicBoolean(resourcePacks.isEmpty());
                java.util.concurrent.atomic.AtomicInteger nextResourcePackChunk =
                        new java.util.concurrent.atomic.AtomicInteger();
                List<TransportMessage> pendingChunkRequests = new ArrayList<>();
                java.util.concurrent.atomic.AtomicLong lastMovementNanos =
                        new java.util.concurrent.atomic.AtomicLong();
                NexoraMovementValidator movementValidator = new NexoraMovementValidator();
                AtomicReference<NexoraMovementValidator.Position> playerPosition =
                        new AtomicReference<>(new NexoraMovementValidator.Position(
                                worldInfo.spawnX() + 0.5, worldInfo.spawnY(),
                                worldInfo.spawnZ() + 0.5));
                UUID playerId = UUID.nameUUIDFromBytes(("nexora-player:"
                        + hello.playerName().toLowerCase(Locale.ROOT))
                        .getBytes(StandardCharsets.UTF_8));
                if (playerSessions.values().stream().anyMatch(active ->
                        active.playerId.equals(playerId))) {
                    connection.close();
                    return;
                }
                final PlayerInventory inventory;
                try {
                    PlayerInventory existing = inventories.get(playerId);
                    if (existing != null) {
                        inventory = existing;
                    } else {
                        PlayerInventory loaded = inventoryStorage == null
                                ? new PlayerInventory(playerId) : inventoryStorage.load(playerId);
                        PlayerInventory raced = inventories.putIfAbsent(playerId, loaded);
                        inventory = raced == null ? loaded : raced;
                    }
                } catch (IOException storageFailure) {
                    connection.close();
                    return;
                }
                PlayerSession session = new PlayerSession(playerId, hello.playerName(), hello.skinIndex(),
                        playerPosition, inventory);
                synchronized (connection) {
                    if (!connection.isOpen()) return;
                    activeSession.set(session);
                    playerSessions.put(connection, session);
                    NexoraMovementValidator.Position spawn = session.position.get();
                    entities.spawn(WorldEntity.player(session.playerId, session.playerName,
                            spawn.x(), spawn.y(), spawn.z(), session.yaw.get(), session.skinIndex));
                    PlayerChunkStreamingManager streaming = chunkStreamingManagerSupplier.get();
                    if (streaming != null) {
                        streaming.updatePlayer(session.playerId,
                                (int) Math.floor(spawn.x()), (int) Math.floor(spawn.z()));
                    }
                }
                synchronizeNewPlayer(connection, session);
                if (send(connection, NexoraInventorySyncCodec.encode(session.inventory))
                        != TransportSendResult.ACCEPTED) {
                    connection.close();
                    return;
                }
                connection.setMessageListener(inboundMessage -> {
                    ByteBuffer payload = inboundMessage.payload();
                    if (isMovementPacket(payload)) {
                        handleMovement(connection, inboundMessage, session,
                                movementValidator, lastMovementNanos);
                    } else if (isInventoryActionPacket(payload)) {
                        handleInventoryAction(connection, inboundMessage, session);
                    } else if (isBlockActionPacket(payload)) {
                        handleBlockAction(connection, inboundMessage, session);
                    } else if (isProtocolPacketType(payload, 17)) {
                        handleResourcePackChunkRequest(connection, inboundMessage,
                                resourcePacks, nextResourcePackChunk);
                    } else if (isProtocolPacketType(payload, 19)) {
                        try {
                            if (inboundMessage.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                                    || inboundMessage.sizeBytes() > NexoraPrivateProtocolCodec.MAX_MESSAGE_BYTES) {
                                connection.close();
                                return;
                            }
                            NexoraPrivateProtocolCodec.decodeResourcePackReady(payload);
                            int expectedChunks = resourcePacks.isEmpty() ? 0
                                    : (resourcePacks.get(0).archiveSize()
                                            + NexoraPrivateProtocolCodec.RESOURCE_PACK_CHUNK_BYTES - 1)
                                            / NexoraPrivateProtocolCodec.RESOURCE_PACK_CHUNK_BYTES;
                            if (resourcePacks.isEmpty()
                                    || nextResourcePackChunk.get() != expectedChunks
                                    || !resourcePackReady.compareAndSet(false, true)) {
                                connection.close();
                                return;
                            }
                            List<TransportMessage> queued;
                            synchronized (pendingChunkRequests) {
                                queued = List.copyOf(pendingChunkRequests);
                                pendingChunkRequests.clear();
                            }
                            for (TransportMessage request : queued) {
                                handleChunkRequest(connection, request, session, requestedChunks);
                                if (!connection.isOpen()) return;
                            }
                        } catch (RuntimeException invalidReady) {
                            connection.close();
                        }
                    } else if (!resourcePackReady.get()) {
                        if (inboundMessage.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                                || inboundMessage.sizeBytes() > NexoraChunkDataCodec.MAX_MESSAGE_BYTES) {
                            connection.close();
                            return;
                        }
                        synchronized (pendingChunkRequests) {
                            if (pendingChunkRequests.size() >= 9) {
                                connection.close();
                            } else {
                                pendingChunkRequests.add(inboundMessage);
                            }
                        }
                    } else {
                        handleChunkRequest(connection, inboundMessage, session, requestedChunks);
                    }
                });
            }
            if (!resourcePacks.isEmpty()) {
                if (send(connection, NexoraPrivateProtocolCodec.encodeResourcePackInfo(
                        resourcePacks.get(0))) != TransportSendResult.ACCEPTED) {
                    connection.close();
                    return;
                }
            }
            try {
                readyListener.accept(connection);
            } catch (RuntimeException listenerFailure) {
                connection.close();
            }
        });
    }

    private void handleResourcePackChunkRequest(
            TransportConnection connection,
            TransportMessage message,
            List<BedrockResourcePackTransferCodec.Pack> packs,
            java.util.concurrent.atomic.AtomicInteger nextChunk) {
        if (!connection.isOpen() || message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                || message.sizeBytes() > NexoraPrivateProtocolCodec.MAX_MESSAGE_BYTES
                || packs.isEmpty()) {
            connection.close();
            return;
        }
        try {
            NexoraPrivateProtocolCodec.ResourcePackChunkRequest request =
                    NexoraPrivateProtocolCodec.decodeResourcePackChunkRequest(message.payload());
            BedrockResourcePackTransferCodec.Pack pack = packs.get(0);
            int expected = nextChunk.get();
            if (!request.resourceName().equals(pack.resourceName())
                    || request.chunkIndex() != expected) {
                connection.close();
                return;
            }
            byte[] chunk = NexoraPrivateProtocolCodec.encodeResourcePackChunk(pack, expected);
            if (send(connection, chunk) != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            nextChunk.incrementAndGet();
        } catch (RuntimeException invalidRequest) {
            connection.close();
        }
    }

    private static boolean isProtocolPacketType(ByteBuffer payload, int type) {
        ByteBuffer input = payload.slice();
        return input.remaining() >= 7
                && input.get(input.position()) == 'N'
                && input.get(input.position() + 1) == 'X'
                && input.get(input.position() + 2) == 'R'
                && input.get(input.position() + 3) == 'A'
                && Byte.toUnsignedInt(input.get(input.position() + 6)) == type;
    }

    private void handleMovement(
            TransportConnection connection, TransportMessage message,
            PlayerSession session,
            NexoraMovementValidator validator,
            java.util.concurrent.atomic.AtomicLong lastMovementNanos) {
        if (!connection.isOpen()) return;
        if (message.deliveryMode() != DeliveryMode.UNRELIABLE
                && message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED) {
            connection.close();
            return;
        }
        long now = System.nanoTime();
        long previous = lastMovementNanos.get();
        if (previous != 0L && now - previous < 50_000_000L) {
            return;
        }
        if (!lastMovementNanos.compareAndSet(previous, now)) {
            return;
        }
        try {
            NexoraMovementValidator.Position requested =
                    NexoraMovementPacketCodec.decodeClientMove(message.payload());
            World world = Objects.requireNonNull(worldSupplier.get(), "world");
            NexoraMovementValidator.Result result =
                    validator.validate(world, session.position.get(), requested);
            if (result.accepted()) {
                NexoraMovementValidator.Position previousPosition = session.position.get();
                double dx = result.position().x() - previousPosition.x();
                double dz = result.position().z() - previousPosition.z();
                if (dx * dx + dz * dz > 0.0001) {
                    session.yaw.set(Math.toDegrees(Math.atan2(-dz, -dx)));
                }
                session.position.set(result.position());
                PlayerChunkStreamingManager streaming = chunkStreamingManagerSupplier.get();
                if (streaming != null) {
                    streaming.updatePlayer(session.playerId,
                            (int) Math.floor(result.position().x()),
                            (int) Math.floor(result.position().z()));
                }
                entities.updatePosition(session.playerId, result.position().x(),
                        result.position().y(), result.position().z(), session.yaw.get());
                ItemPickupService.PickupResult pickup = new ItemPickupService(entities, world).pickup(
                        session.playerId, result.position().x(), result.position().y(),
                        result.position().z(), session.inventory);
                if (pickup.collected() > 0 && !persistInventory(session.inventory)) {
                    connection.close();
                    return;
                }
                if (pickup.collected() > 0 && send(connection,
                        NexoraInventorySyncCodec.encode(session.inventory))
                        != TransportSendResult.ACCEPTED) {
                    connection.close();
                    return;
                }
            }
            NexoraMovementValidator.Position authoritative = session.position.get();
            if (send(connection, NexoraMovementPacketCodec.encodeServerPosition(authoritative))
                    != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            if (result.accepted()) {
                broadcastPlayerState(connection, session);
            }
        } catch (RuntimeException malformedMovement) {
            connection.close();
        }
    }

    private void handleInventoryAction(TransportConnection connection,
                                       TransportMessage message, PlayerSession session) {
        if (!connection.isOpen()) return;
        if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                || message.sizeBytes() > 32) {
            connection.close();
            return;
        }
        NexoraInventoryActionCodec.Action action = null;
        NexoraInventoryActionCodec.Result result;
        try {
            NexoraInventoryActionCodec.Request request =
                    NexoraInventoryActionCodec.decodeRequest(message.payload());
            action = request.action();
            int slot = request.sourceSlot();
            switch (action) {
                case MOVE_OR_SWAP -> result = session.inventory.moveOrSwap(slot, request.targetSlot())
                        ? NexoraInventoryActionCodec.Result.OK
                        : (session.inventory.stackAt(slot) == null
                            ? NexoraInventoryActionCodec.Result.EMPTY_SLOT
                            : NexoraInventoryActionCodec.Result.INVALID_SLOT);
                case DROP_ONE -> {
                    ItemStack dropped = session.inventory.removeOne(slot);
                    if (dropped == null) {
                        result = NexoraInventoryActionCodec.Result.EMPTY_SLOT;
                    } else {
                        spawnDroppedItems(session, dropped);
                        result = NexoraInventoryActionCodec.Result.OK;
                    }
                }
                case DROP_STACK -> {
                    ItemStack dropped = session.inventory.removeStack(slot);
                    if (dropped == null) {
                        result = NexoraInventoryActionCodec.Result.EMPTY_SLOT;
                    } else {
                        spawnDroppedItems(session, dropped);
                        result = NexoraInventoryActionCodec.Result.OK;
                    }
                }
                case USE -> result = session.inventory.stackAt(slot) == null
                        ? NexoraInventoryActionCodec.Result.EMPTY_SLOT
                        : NexoraInventoryActionCodec.Result.NOT_USABLE;
                default -> throw new IllegalStateException("Unhandled inventory action");
            }
        } catch (RuntimeException malformed) {
            connection.close();
            return;
        }
        if (!persistInventory(session.inventory)) {
            connection.close();
            return;
        }
        if (send(connection, NexoraInventoryActionCodec.encodeResult(action, result))
                != TransportSendResult.ACCEPTED
                || send(connection, NexoraInventorySyncCodec.encode(session.inventory))
                != TransportSendResult.ACCEPTED) {
            connection.close();
        }
    }

    private boolean persistInventory(PlayerInventory inventory) {
        if (inventoryStorage == null) return true;
        try {
            inventoryStorage.save(inventory);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private void spawnDroppedItems(PlayerSession session, ItemStack stack) {
        NexoraMovementValidator.Position position = session.position.get();
        for (int index = 0; index < stack.count(); index++) {
            double verticalOffset = (index % 4) * 0.03;
            entities.spawn(new WorldEntity(UUID.randomUUID(), WorldEntity.Type.ITEM,
                    stack.itemId(), position.x(), position.y() + 0.35 + verticalOffset,
                    position.z(), 0.0, -1));
        }
    }

    private void synchronizeNewPlayer(TransportConnection newcomer, PlayerSession joining) {
        for (Map.Entry<TransportConnection, PlayerSession> entry : playerSessions.entrySet()) {
            TransportConnection peer = entry.getKey();
            PlayerSession existing = entry.getValue();
            if (peer == newcomer || !peer.isOpen()
                    || !EntityVisibility.isWithin(joining.position.get(), existing.position.get(),
                    PLAYER_VISIBILITY_RADIUS)) {
                continue;
            }
            if (joining.visiblePlayers.add(existing.playerId)
                    && send(newcomer, encodePlayerState(existing)) != TransportSendResult.ACCEPTED) {
                newcomer.close();
                return;
            }
            if (existing.visiblePlayers.add(joining.playerId)
                    && send(peer, encodePlayerState(joining)) != TransportSendResult.ACCEPTED) {
                peer.close();
            }
        }
    }

    /** Replicates player entities only to connections whose visibility radius contains them. */
    private void broadcastPlayerState(TransportConnection sender, PlayerSession session) {
        byte[] statePacket = encodePlayerState(session);
        for (Map.Entry<TransportConnection, PlayerSession> entry : playerSessions.entrySet()) {
            TransportConnection peer = entry.getKey();
            PlayerSession other = entry.getValue();
            if (peer == sender || !peer.isOpen()) continue;

            boolean visibleNow = EntityVisibility.isWithin(
                    session.position.get(), other.position.get(), PLAYER_VISIBILITY_RADIUS);
            boolean wasVisible = session.visiblePlayers.contains(other.playerId);
            if (visibleNow) {
                session.visiblePlayers.add(other.playerId);
                other.visiblePlayers.add(session.playerId);
                if (!wasVisible && send(sender, encodePlayerState(other))
                        != TransportSendResult.ACCEPTED) {
                    sender.close();
                    return;
                }
                if (send(peer, statePacket) != TransportSendResult.ACCEPTED) peer.close();
            } else if (wasVisible) {
                session.visiblePlayers.remove(other.playerId);
                other.visiblePlayers.remove(session.playerId);
                if (send(sender, NexoraPlayerSyncCodec.encodePlayerLeft(other.playerId))
                        != TransportSendResult.ACCEPTED) {
                    sender.close();
                    return;
                }
                if (send(peer, NexoraPlayerSyncCodec.encodePlayerLeft(session.playerId))
                        != TransportSendResult.ACCEPTED) peer.close();
            }
        }
    }

    private void broadcastPlayerLeft(TransportConnection leaver, UUID playerId) {
        byte[] packet = NexoraPlayerSyncCodec.encodePlayerLeft(playerId);
        for (Map.Entry<TransportConnection, PlayerSession> entry : playerSessions.entrySet()) {
            TransportConnection peer = entry.getKey();
            PlayerSession session = entry.getValue();
            if (peer == leaver || !peer.isOpen()) continue;
            if (session.visiblePlayers.remove(playerId)
                    && send(peer, packet) != TransportSendResult.ACCEPTED) {
                peer.close();
            }
        }
    }

    private static byte[] encodePlayerState(PlayerSession session) {
        NexoraMovementValidator.Position position = session.position.get();
        return NexoraPlayerSyncCodec.encodePlayerState(
                new NexoraPlayerSyncCodec.PlayerState(
                        session.playerId, session.playerName, session.skinIndex, position.x(), position.y(), position.z(), session.yaw.get()));
    }

    private static final class PlayerSession {
        private final UUID playerId;
        private final String playerName;
        private final int skinIndex;
        private final AtomicReference<NexoraMovementValidator.Position> position;
        private final PlayerInventory inventory;
        private final AtomicReference<Double> yaw = new AtomicReference<>(0.0);
        private final java.util.Set<UUID> visiblePlayers = ConcurrentHashMap.newKeySet();

        private PlayerSession(UUID playerId, String playerName, int skinIndex,
                              AtomicReference<NexoraMovementValidator.Position> position,
                              PlayerInventory inventory) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.skinIndex = skinIndex;
            this.position = position;
            this.inventory = inventory;
        }
    }

    private static boolean isInventoryActionPacket(ByteBuffer payload) {
        ByteBuffer input = payload.slice();
        return input.remaining() >= 7
                && input.get(input.position()) == 'N'
                && input.get(input.position() + 1) == 'X'
                && input.get(input.position() + 2) == 'R'
                && input.get(input.position() + 3) == 'A'
                && Byte.toUnsignedInt(input.get(input.position() + 6)) == 12;
    }

    private static boolean isMovementPacket(ByteBuffer payload) {
        ByteBuffer input = payload.slice();
        return input.remaining() >= 7
                && input.get(input.position()) == 'N'
                && input.get(input.position() + 1) == 'X'
                && input.get(input.position() + 2) == 'R'
                && input.get(input.position() + 3) == 'A'
                && Byte.toUnsignedInt(input.get(input.position() + 6)) == 7;
    }


    private void handleBlockAction(TransportConnection connection,
                                   TransportMessage message, PlayerSession session) {
        if (!connection.isOpen()) return;
        if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED || message.sizeBytes() > 256) {
            connection.close();
            return;
        }
        try {
            NexoraBlockActionCodec.Request request =
                    NexoraBlockActionCodec.decodeRequest(message.payload());
            World world = Objects.requireNonNull(worldSupplier.get(), "world");
            BlockPosition target = new BlockPosition(request.x(), request.y(), request.z());
            if (!world.height().contains(request.y())
                    || !world.isChunkLoaded(target.chunkPosition())) return;
            NexoraMovementValidator.Position player = session.position.get();
            double dx = request.x() + 0.5 - player.x();
            double dy = request.y() + 0.5 - (player.y() + 1.0);
            double dz = request.z() + 0.5 - player.z();
            if (dx * dx + dy * dy + dz * dz > 36.0) return;

            BlockState before = world.blockAt(target);
            BlockState after;
            if (request.action() == NexoraBlockActionCodec.Action.BREAK) {
                if (before.isAir() || "minecraft:bedrock".equals(before.identifier())
                        || !session.inventory.addOne(before.identifier())) return;
                after = BlockState.AIR;
                world.setBlock(target, after);
                if (!persistInventory(session.inventory)) {
                    world.setBlock(target, before);
                    session.inventory.removeOne(findInventorySlot(session.inventory, before.identifier()));
                    connection.close();
                    return;
                }
            } else {
                if (!before.isAir() || request.blockIdentifier().equals("minecraft:air")
                        || !isSafePlacement(target, player)) return;
                int slot = findInventorySlot(session.inventory, request.blockIdentifier());
                if (slot < 0) return;
                ItemStack removed = session.inventory.removeOne(slot);
                after = new BlockState(request.blockIdentifier());
                world.setBlock(target, after);
                if (!persistInventory(session.inventory)) {
                    world.setBlock(target, BlockState.AIR);
                    session.inventory.addOne(removed.itemId());
                    connection.close();
                    return;
                }
            }
            byte[] update = NexoraBlockActionCodec.encodeUpdate(
                    new NexoraBlockActionCodec.Update(request.x(), request.y(), request.z(),
                            after.identifier()));
            for (Map.Entry<TransportConnection, PlayerSession> entry : playerSessions.entrySet()) {
                TransportConnection peer = entry.getKey();
                if (peer.isOpen() && send(peer, update) != TransportSendResult.ACCEPTED) {
                    peer.close();
                }
            }
            if (send(connection, NexoraInventorySyncCodec.encode(session.inventory))
                    != TransportSendResult.ACCEPTED) connection.close();
        } catch (RuntimeException malformed) {
            connection.close();
        }
    }

    private static boolean isSafePlacement(BlockPosition target,
                                           NexoraMovementValidator.Position player) {
        double minX = Math.floor(player.x() - 0.3);
        double maxX = Math.floor(player.x() + 0.3);
        double minY = Math.floor(player.y());
        double maxY = Math.floor(player.y() + 1.7);
        double minZ = Math.floor(player.z() - 0.3);
        double maxZ = Math.floor(player.z() + 0.3);
        return target.x() < minX || target.x() > maxX
                || target.y() < minY || target.y() > maxY
                || target.z() < minZ || target.z() > maxZ;
    }

    private static int findInventorySlot(PlayerInventory inventory, String itemId) {
        ItemStack[] slots = inventory.slotSnapshot();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].itemId().equals(itemId)) return i;
        }
        return -1;
    }

    private static boolean isBlockActionPacket(ByteBuffer payload) {
        ByteBuffer input = payload.slice();
        return input.remaining() >= 7
                && input.get(input.position()) == 'N'
                && input.get(input.position() + 1) == 'X'
                && input.get(input.position() + 2) == 'R'
                && input.get(input.position() + 3) == 'A'
                && Byte.toUnsignedInt(input.get(input.position() + 6)) == 14;
    }

    private void handleChunkRequest(TransportConnection connection, TransportMessage message,
                                    PlayerSession session, java.util.Set<ChunkPosition> requestedChunks) {
        if (!connection.isOpen()) return;
        if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                || message.sizeBytes() > NexoraChunkDataCodec.MAX_MESSAGE_BYTES) {
            connection.close();
            return;
        }
        try {
            NexoraChunkDataCodec.ChunkRequest request =
                    NexoraChunkDataCodec.decodeChunkRequest(message.payload());
            ChunkPosition position = new ChunkPosition(request.chunkX(), request.chunkZ());
            NexoraMovementValidator.Position player = session.position.get();
            int playerChunkX = Math.floorDiv((int) Math.floor(player.x()), 16);
            int playerChunkZ = Math.floorDiv((int) Math.floor(player.z()), 16);
            if (Math.abs((long) request.chunkX() - playerChunkX) > 1
                    || Math.abs((long) request.chunkZ() - playerChunkZ) > 1) {
                connection.close();
                return;
            }
            if (!requestedChunks.contains(position) && requestedChunks.size() >= 512) {
                connection.close();
                return;
            }
            requestedChunks.add(position);
            if (asyncChunkLoader != null) {
                asyncChunkLoader.request(position).whenComplete((available, failure) -> {
                    if (!connection.isOpen()) return;
                    if (failure != null || !Boolean.TRUE.equals(available)) {
                        connection.close();
                        return;
                    }
                    try {
                        World world = Objects.requireNonNull(worldSupplier.get(), "world");
                        NexoraChunkData chunk = NexoraChunkData.volume(
                                world, request.chunkX(), request.chunkZ());
                        if (send(connection, NexoraChunkDataCodec.encodeChunkData(chunk))
                                != TransportSendResult.ACCEPTED) {
                            connection.close();
                        }
                    } catch (RuntimeException unavailableWorld) {
                        connection.close();
                    }
                });
                return;
            }
            if (chunkLoader == null || !Boolean.TRUE.equals(chunkLoader.apply(position))) {
                connection.close();
                return;
            }
            World world = Objects.requireNonNull(worldSupplier.get(), "world");
            NexoraChunkData chunk = NexoraChunkData.volume(
                    world, request.chunkX(), request.chunkZ());
            if (send(connection, NexoraChunkDataCodec.encodeChunkData(chunk))
                    != TransportSendResult.ACCEPTED) {
                connection.close();
            }
        } catch (RuntimeException invalidRequestOrWorld) {
            connection.close();
        }
    }

    private static TransportSendResult send(TransportConnection connection, byte[] payload) {
        return connection.send(ByteBuffer.wrap(payload), DeliveryMode.RELIABLE_ORDERED);
    }

    private static void reject(TransportConnection connection, int code, String reason) {
        connection.setMessageListener(ignored -> connection.close());
        if (send(connection, NexoraPrivateProtocolCodec.encodeReject(code, reason))
                != TransportSendResult.ACCEPTED) {
            connection.close();
        }
    }
}
