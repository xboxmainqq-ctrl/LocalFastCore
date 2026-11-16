package ru.nexora.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.TakeItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Tick-owned Bedrock player state and bounded movement/chunk streaming.
 *
 * <p>Transport callbacks only decode packets and publish immutable input
 * snapshots. Entity and chunk-ticket mutations happen from {@link #tick()} on
 * the server game thread.</p>
 *
 * <p>This is the first movement milestone: it accepts bounded PlayerAuthInput
 * positions, rejects large teleports with a prediction correction, tracks
 * players in the world entity registry, and streams skeleton chunks as players
 * move. Collision resolution, block actions and inventory transactions remain
 * separate gameplay systems.</p>
 */
public final class BedrockPlayerSessionManager implements AutoCloseable {
    private static final int VIEW_DISTANCE_CHUNKS = 1;
    private static final int CHUNK_SIZE = 16;
    private static final double MAX_COORDINATE = 30_000_000d;
    private static final int MAX_PACKETS_PER_MESSAGE = 128;

    private final World world;
    private final EntityRegistry entities;
    private final PlayerChunkStreamingManager chunkStreaming;
    private final BedrockNetworkSettings networkSettings;
    private final BedrockRuntimeBlockRegistry blockRegistry;
    private final List<ChunkPosition> initialChunks;
    private final int spawnY;
    private final Consumer<String> log;
    private final Map<UUID, PlayerInventory> inventories;
    private final FilePlayerInventoryStorage inventoryStorage;
    private final ItemPickupService itemPickupService;
    private final BedrockItemRegistry itemRegistry;
    private final ConcurrentMap<String, PlayerSession> byConnection = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PlayerSession> byPlayer = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextRuntimeEntityId = new AtomicLong(1L);
    private final ConcurrentMap<UUID, Long> runtimeEntityIds = new ConcurrentHashMap<>();

    public BedrockPlayerSessionManager(
            World world,
            EntityRegistry entities,
            PlayerChunkStreamingManager chunkStreaming,
            BedrockNetworkSettings networkSettings,
            List<ChunkPosition> initialChunks,
            int spawnY,
            Consumer<String> log
    ) {
        this(world, entities, chunkStreaming, networkSettings, initialChunks, spawnY,
                BedrockBlockCompatibilityRegistry.builtIn().adapt(
                        BedrockRuntimeBlockRegistryProfile.hashing().registry()), log);
    }

    public BedrockPlayerSessionManager(
            World world,
            EntityRegistry entities,
            PlayerChunkStreamingManager chunkStreaming,
            BedrockNetworkSettings networkSettings,
            List<ChunkPosition> initialChunks,
            int spawnY,
            BedrockRuntimeBlockRegistry blockRegistry,
            Consumer<String> log
    ) {
        this(world, entities, chunkStreaming, networkSettings, initialChunks, spawnY,
                blockRegistry, log, new ConcurrentHashMap<>(), null);
    }

    public BedrockPlayerSessionManager(
            World world,
            EntityRegistry entities,
            PlayerChunkStreamingManager chunkStreaming,
            BedrockNetworkSettings networkSettings,
            List<ChunkPosition> initialChunks,
            int spawnY,
            BedrockRuntimeBlockRegistry blockRegistry,
            Consumer<String> log,
            Map<UUID, PlayerInventory> inventories,
            FilePlayerInventoryStorage inventoryStorage
    ) {
        this.world = Objects.requireNonNull(world, "world");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.chunkStreaming = Objects.requireNonNull(chunkStreaming, "chunkStreaming");
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        this.blockRegistry = Objects.requireNonNull(blockRegistry, "blockRegistry");
        this.initialChunks = List.copyOf(Objects.requireNonNull(initialChunks, "initialChunks"));
        this.log = Objects.requireNonNull(log, "log");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.inventoryStorage = inventoryStorage;
        this.itemPickupService = new ItemPickupService(this.entities, this.world);
        this.itemRegistry = BedrockItemRegistry.loadBundled();
        if (this.initialChunks.isEmpty() || this.initialChunks.size() > 128) {
            throw new IllegalArgumentException("Initial Bedrock chunk set must contain 1 to 128 chunks");
        }
        if (spawnY < world.height().minY() || spawnY >= world.height().maxYExclusive()) {
            throw new IllegalArgumentException("Bedrock spawn Y is outside world height");
        }
        this.spawnY = spawnY;
    }

    /** Called by the post-login StartGame settings factory; no world state is mutated here. */
    public void onAuthenticated(TransportConnection connection, BedrockAuthenticatedPlayer player) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(player, "player");
        if (closed.get() || !connection.isOpen()) {
            connection.close();
            return;
        }
        UUID playerId = UUID.nameUUIDFromBytes(
                ("nexora-bedrock:" + player.identityId()).getBytes(StandardCharsets.UTF_8));
        PlayerSession session = new PlayerSession(connection, player, playerId,
                0.5d, spawnY, 0.5d, 0.0d);
        PlayerSession existingConnection = byConnection.putIfAbsent(connection.id(), session);
        if (existingConnection != null) {
            connection.close();
            return;
        }
        PlayerSession previousPlayer = byPlayer.put(playerId, session);
        if (previousPlayer != null) {
            previousPlayer.closed.set(true);
            log.accept("Replacing an existing Bedrock session for XUID " + player.identityId());
        }
    }

    /** Marks a session for tick-thread cleanup after the transport closes. */
    public void onDisconnected(TransportConnection connection) {
        if (connection == null) return;
        PlayerSession session = byConnection.get(connection.id());
        if (session != null) {
            session.closed.set(true);
        }
    }

    /** Receives the post-login gameplay stream. This callback may run on a Netty I/O thread. */
    public void accept(TransportConnection connection, TransportMessage message) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");
        if (closed.get() || !connection.isOpen()) return;
        PlayerSession session = byConnection.get(connection.id());
        if (session == null || session.closed.get()) return;

        try {
            ByteBuffer batch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                    message.payload(), networkSettings);
            List<BedrockGamePacket> packets = BedrockPacketBatchCodec.decodeUncompressed(batch);
            if (packets.isEmpty() || packets.size() > MAX_PACKETS_PER_MESSAGE) {
                throw new IllegalArgumentException("Invalid Bedrock gameplay packet count");
            }
            for (BedrockGamePacket packet : packets) {
                ByteBuf payload = Unpooled.wrappedBuffer(packet.payload());
                try {
                    BedrockPacket decoded = Bedrock_v2225.CODEC.tryDecode(
                            Bedrock_v2225.CODEC.createHelper(),
                            payload,
                            packet.header().packetId(),
                            PacketRecipient.SERVER);
                    if (decoded instanceof PlayerAuthInputPacket input) {
                        session.pendingInput.set(PlayerInput.from(input));
                    } else if (decoded instanceof PlayerActionPacket action) {
                        PlayerActionType type = action.getAction();
                        Vector3i block = action.getBlockPosition();
                        if (type != null && block != null && isBlockAction(type)) {
                            session.enqueueBlockAction(new BlockAction(type,
                                    new BlockPosition(block.getX(), block.getY(), block.getZ())));
                        }
                    } else if (decoded instanceof RequestChunkRadiusPacket request) {
                        session.requestedRadius.set(Math.max(1,
                                Math.min(VIEW_DISTANCE_CHUNKS, request.getRadius())));
                    }
                } finally {
                    payload.release();
                }
            }
        } catch (Exception malformed) {
            log.accept("Closing malformed Bedrock gameplay stream for "
                    + session.player.displayName() + ": " + malformed.getMessage());
            connection.close();
            session.closed.set(true);
        }
    }

    /** Called once per server tick. All entity and chunk-ticket mutations happen here. */
    public void tick() {
        if (closed.get()) return;
        List<PlayerSession> snapshot = new ArrayList<>(byConnection.values());

        // Remove old/replaced sessions first so a reconnect with the same XUID can respawn safely.
        for (PlayerSession session : snapshot) {
            if (session.closed.get() || !session.connection.isOpen()) {
                removeSession(session);
            }
        }

        snapshot = new ArrayList<>(byConnection.values());
        for (PlayerSession session : snapshot) {
            if (session.closed.get() || !session.connection.isOpen()) continue;
            try {
                ensureSpawned(session);
                applyPendingInput(session);
                processBlockActions(session);
                if (session.playReady) {
                    if (!session.inventorySynced) {
                        session.inventorySynced = sendInventory(session, true);
                    }
                    if (session.inventorySynced) {
                        syncVisibleItemEntities(session);
                        collectNearbyItems(session);
                    }
                    publishChunkCenter(session);
                    acknowledgeChunkRadius(session);
                    sendNewChunks(session);
                }
            } catch (RuntimeException failure) {
                log.accept("Bedrock gameplay tick failed for " + session.player.displayName()
                        + ": " + failure.getMessage());
                session.closed.set(true);
                session.connection.close();
            }
        }
    }

    private void ensureSpawned(PlayerSession session) {
        if (session.entitySpawned) return;
        entities.spawn(WorldEntity.player(session.playerId, session.player.displayName(),
                session.x, session.y, session.z, session.yaw, 0));
        inventories.computeIfAbsent(session.playerId, id -> {
            if (inventoryStorage == null) return new PlayerInventory(id);
            try {
                return inventoryStorage.load(id);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not load inventory for " + id, failure);
            }
        });
        chunkStreaming.updatePlayer(session.playerId, floorToInt(session.x), floorToInt(session.z));
        session.sentChunks.addAll(initialChunks);
        session.entitySpawned = true;
    }

    private void applyPendingInput(PlayerSession session) {
        PlayerInput input = session.pendingInput.getAndSet(null);
        if (input == null) return;
        if (input.tick() <= session.lastInputTick) return;

        long previousTick = session.lastInputTick;
        session.lastInputTick = input.tick();
        if (!input.finite()) {
            sendCorrection(session, input.tick());
            return;
        }

        double x = input.x();
        double y = input.y();
        double z = input.z();
        WorldHeight height = world.height();
        if (Math.abs(x) > MAX_COORDINATE || Math.abs(z) > MAX_COORDINATE
                || y < height.minY() - 2.0d || y > height.maxYExclusive() + 2.0d) {
            sendCorrection(session, input.tick());
            return;
        }

        long elapsedTicks = previousTick < 0 ? 1 : Math.max(1, input.tick() - previousTick);
        double maximumStep = Math.min(16.0d, Math.max(3.0d, elapsedTicks * 1.25d));
        double dx = x - session.x;
        double dy = y - session.y;
        double dz = z - session.z;
        if (dx * dx + dy * dy + dz * dz > maximumStep * maximumStep) {
            sendCorrection(session, input.tick());
            return;
        }

        PlayerCollisionResolver.Resolution resolved = PlayerCollisionResolver.resolve(
                world, session.x, session.y, session.z, x, y, z);
        session.x = resolved.x();
        session.y = resolved.y();
        session.z = resolved.z();
        session.yaw = normalizeYaw(input.yaw());
        session.onGround = resolved.onGround();
        session.playReady = true;
        entities.updatePosition(session.playerId, session.x, session.y, session.z, session.yaw);
        chunkStreaming.updatePlayer(session.playerId, floorToInt(session.x), floorToInt(session.z));
        if (resolved.collided()) {
            sendCorrection(session, input.tick());
        }
    }

    private static boolean isBlockAction(PlayerActionType action) {
        return action == PlayerActionType.START_BREAK
                || action == PlayerActionType.ABORT_BREAK
                || action == PlayerActionType.STOP_BREAK
                || action == PlayerActionType.BLOCK_PREDICT_DESTROY
                || action == PlayerActionType.BLOCK_CONTINUE_DESTROY;
    }

    /** Applies client block actions on the game thread, never on a Netty I/O thread. */
    private void processBlockActions(PlayerSession session) {
        BlockAction action;
        while ((action = session.pollBlockAction()) != null) {
            switch (action.type()) {
                case START_BREAK, BLOCK_PREDICT_DESTROY, BLOCK_CONTINUE_DESTROY ->
                        session.breakingBlock = action.position();
                case ABORT_BREAK -> {
                    if (action.position().equals(session.breakingBlock)) {
                        session.breakingBlock = null;
                    }
                }
                case STOP_BREAK -> {
                    if (action.position().equals(session.breakingBlock)) {
                        session.breakingBlock = null;
                        BlockBreakService.Outcome outcome = BlockBreakService.breakBlock(
                                world, action.position(), session.x, session.y, session.z);
                        if (outcome.broken()) {
                            String drop = BlockDropTable.dropFor(outcome.previousState());
                            if (drop != null) {
                                entities.spawn(new WorldEntity(UUID.randomUUID(), WorldEntity.Type.ITEM,
                                        drop, action.position().x() + 0.5d,
                                        action.position().y() + 0.5d,
                                        action.position().z() + 0.5d, 0.0d, -1));
                            }
                            broadcastBlockUpdate(action.position(), BlockState.AIR);
                        }
                    }
                }
                default -> {
                    // Other PlayerActionPacket actions are not block mutations.
                }
            }
        }
    }

    private void collectNearbyItems(PlayerSession session) {
        PlayerInventory inventory = inventories.get(session.playerId);
        if (inventory == null) return;
        List<WorldEntity> nearbyBeforePickup = entities.within(
                session.x, session.y, session.z, ItemPickupService.DEFAULT_PICKUP_RADIUS);
        ItemPickupService.PickupResult result = itemPickupService.pickup(
                session.playerId, session.x, session.y, session.z, inventory);
        if (result.collected() > 0) {
            for (WorldEntity candidate : nearbyBeforePickup) {
                if (candidate.type() == WorldEntity.Type.ITEM
                        && entities.find(candidate.id()).isEmpty()) {
                    broadcastItemTaken(candidate, session);
                }
            }
            sendInventory(session, false);
        }
        if (result.collected() > 0 && inventoryStorage != null) {
            try {
                inventoryStorage.save(inventory);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not save inventory for " + session.playerId, failure);
            }
        }
    }

    /**
     * Mirrors nearby authoritative item entities to each Bedrock client.
     * A session tracks its own visible set so late joiners and moving players
     * receive spawns/despawns without duplicate entity packets.
     */
    private void syncVisibleItemEntities(PlayerSession session) {
        Set<UUID> desired = new HashSet<>();
        for (WorldEntity entity : entities.within(session.x, session.y, session.z, 32.0d)) {
            if (entity.type() != WorldEntity.Type.ITEM || !isEntityChunkLoaded(entity)) continue;
            desired.add(entity.id());
            if (session.visibleItemEntities.contains(entity.id())) continue;

            AddItemEntityPacket spawn = new AddItemEntityPacket();
            long runtimeId = runtimeEntityId(entity.id());
            spawn.setUniqueEntityId(runtimeId);
            spawn.setRuntimeEntityId(runtimeId);
            spawn.setItemInHand(itemRegistry.itemData(entity.name(), 1));
            spawn.setPosition(Vector3f.from((float) entity.x(), (float) entity.y(), (float) entity.z()));
            spawn.setMotion(Vector3f.from(0.0f, 0.08f, 0.0f));
            if (sendProtocolPacket(session.connection, spawn)) {
                session.visibleItemEntities.add(entity.id());
            }
        }

        for (UUID previouslyVisible : new HashSet<>(session.visibleItemEntities)) {
            if (desired.contains(previouslyVisible)) continue;
            Long runtimeId = runtimeEntityIds.get(previouslyVisible);
            if (runtimeId != null) {
                RemoveEntityPacket remove = new RemoveEntityPacket();
                remove.setUniqueEntityId(runtimeId);
                sendProtocolPacket(session.connection, remove);
            }
            session.visibleItemEntities.remove(previouslyVisible);
        }
    }

    private boolean isEntityChunkLoaded(WorldEntity entity) {
        int blockX = floorToInt(entity.x());
        int blockZ = floorToInt(entity.z());
        return world.isChunkLoaded(new ChunkPosition(Math.floorDiv(blockX, CHUNK_SIZE),
                Math.floorDiv(blockZ, CHUNK_SIZE)));
    }

    private long runtimeEntityId(UUID entityId) {
        return runtimeEntityIds.computeIfAbsent(entityId, ignored -> {
            long value = nextRuntimeEntityId.getAndIncrement();
            if (value <= 0L) {
                throw new IllegalStateException("Bedrock runtime entity ID space exhausted");
            }
            return value;
        });
    }

    private void broadcastItemTaken(WorldEntity item, PlayerSession collector) {
        Long itemRuntimeId = runtimeEntityIds.get(item.id());
        if (itemRuntimeId == null) return;
        long collectorRuntimeId = runtimeEntityId(collector.playerId);
        for (PlayerSession viewer : new ArrayList<>(byConnection.values())) {
            if (viewer.closed.get() || !viewer.connection.isOpen()
                    || !viewer.visibleItemEntities.remove(item.id())) continue;
            TakeItemEntityPacket taken = new TakeItemEntityPacket();
            taken.setItemRuntimeEntityId(itemRuntimeId);
            taken.setRuntimeEntityId(collectorRuntimeId);
            sendProtocolPacket(viewer.connection, taken);
        }
        runtimeEntityIds.remove(item.id(), itemRuntimeId);
    }

    /** Sends the authoritative 36-slot Bedrock player inventory and, on spawn, selects hotbar slot zero. */
    private boolean sendInventory(PlayerSession session, boolean includeHotbarSelection) {
        PlayerInventory inventory = inventories.get(session.playerId);
        if (inventory == null || inventory.slotCount() != PlayerInventory.DEFAULT_SLOTS) {
            throw new IllegalStateException("Bedrock player inventory must contain exactly 36 slots");
        }
        List<ItemData> contents = new ArrayList<>(PlayerInventory.DEFAULT_SLOTS);
        for (int slot = 0; slot < PlayerInventory.DEFAULT_SLOTS; slot++) {
            ItemStack stack = inventory.stackAt(slot);
            contents.add(stack == null ? ItemData.AIR
                    : itemRegistry.itemData(stack.itemId(), stack.count()));
        }

        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(ContainerId.INVENTORY);
        content.setContents(contents);
        content.setContainerNameData(new FullContainerName(
                ContainerSlotType.HOTBAR_AND_INVENTORY, null));
        if (!sendProtocolPacket(session.connection, content)) {
            return false;
        }
        if (includeHotbarSelection) {
            PlayerHotbarPacket hotbar = new PlayerHotbarPacket();
            hotbar.setSelectedHotbarSlot(0);
            hotbar.setContainerId(ContainerId.INVENTORY);
            hotbar.setSelectHotbarSlot(true);
            return sendProtocolPacket(session.connection, hotbar);
        }
        return true;
    }

    private void broadcastBlockUpdate(BlockPosition position, BlockState state) {
        UpdateBlockPacket update = new UpdateBlockPacket();
        update.setBlockPosition(Vector3i.from(position.x(), position.y(), position.z()));
        update.getFlags().add(UpdateBlockPacket.Flag.NETWORK);
        update.setDataLayer(0);
        update.setDefinition(() -> blockRegistry.runtimeId(state));
        for (PlayerSession viewer : new ArrayList<>(byConnection.values())) {
            if (!viewer.closed.get() && viewer.connection.isOpen() && viewer.playReady) {
                sendProtocolPacket(viewer.connection, update);
            }
        }
    }

    private void sendCorrection(PlayerSession session, long tick) {
        CorrectPlayerMovePredictionPacket correction = new CorrectPlayerMovePredictionPacket();
        correction.setPosition(Vector3f.from((float) session.x, (float) session.y, (float) session.z));
        correction.setDelta(Vector3f.from(0.0f, 0.0f, 0.0f));
        correction.setOnGround(session.onGround);
        correction.setTick(tick);
        sendProtocolPacket(session.connection, correction);
    }

    private void acknowledgeChunkRadius(PlayerSession session) {
        int requested = session.requestedRadius.get();
        if (requested < 0 || session.acknowledgedRadius == VIEW_DISTANCE_CHUNKS) return;
        ChunkRadiusUpdatedPacket response = new ChunkRadiusUpdatedPacket();
        response.setRadius(VIEW_DISTANCE_CHUNKS);
        if (sendProtocolPacket(session.connection, response)) {
            session.acknowledgedRadius = VIEW_DISTANCE_CHUNKS;
        }
    }

    private void publishChunkCenter(PlayerSession session) {
        ChunkPosition center = ChunkPosition.fromBlock(floorToInt(session.x), floorToInt(session.z));
        if (center.equals(session.lastPublishedCenter)) return;
        NetworkChunkPublisherUpdatePacket update = new NetworkChunkPublisherUpdatePacket();
        update.setPosition(Vector3i.from(floorToInt(session.x), floorToInt(session.y), floorToInt(session.z)));
        update.setRadius(VIEW_DISTANCE_CHUNKS * CHUNK_SIZE);
        if (sendProtocolPacket(session.connection, update)) {
            session.lastPublishedCenter = center;
        }
    }

    private void sendNewChunks(PlayerSession session) {
        ChunkPosition center = ChunkPosition.fromBlock(floorToInt(session.x), floorToInt(session.z));
        Set<ChunkPosition> desired = desiredChunks(center);
        session.sentChunks.retainAll(desired);
        List<ChunkPosition> ordered = new ArrayList<>(desired);
        ordered.sort(Comparator.comparingInt(ChunkPosition::x).thenComparingInt(ChunkPosition::z));
        for (ChunkPosition position : ordered) {
            if (session.sentChunks.contains(position) || !world.isChunkLoaded(position)) continue;
            BedrockGamePacket packet = BedrockLevelChunkPacketCodec.encodeSkeletonPacket(
                    new BedrockLevelChunkPacketCodec.ChunkPosition(position.x(), position.z()),
                    0, BedrockLevelChunkPacketCodec.SUBCHUNK_REQUEST_SENTINEL);
            TransportSendResult result = sendGamePacket(session.connection, packet);
            if (result == TransportSendResult.ACCEPTED) {
                session.sentChunks.add(position);
            } else if (result == TransportSendResult.CLOSED) {
                session.closed.set(true);
                return;
            }
        }
    }

    private Set<ChunkPosition> desiredChunks(ChunkPosition center) {
        Set<ChunkPosition> result = new HashSet<>();
        int radiusSquared = VIEW_DISTANCE_CHUNKS * VIEW_DISTANCE_CHUNKS;
        for (int dx = -VIEW_DISTANCE_CHUNKS; dx <= VIEW_DISTANCE_CHUNKS; dx++) {
            for (int dz = -VIEW_DISTANCE_CHUNKS; dz <= VIEW_DISTANCE_CHUNKS; dz++) {
                if (dx * dx + dz * dz > radiusSquared) continue;
                result.add(new ChunkPosition(center.x() + dx, center.z() + dz));
            }
        }
        return result;
    }

    private boolean sendProtocolPacket(TransportConnection connection, BedrockPacket packet) {
        ByteBuf payload = Unpooled.buffer();
        try {
            Bedrock_v2225.CODEC.tryEncode(Bedrock_v2225.CODEC.createHelper(), payload, packet);
            int packetId = Bedrock_v2225.CODEC.getPacketDefinition(packet.getClass()).id();
            byte[] bytes = new byte[payload.readableBytes()];
            payload.readBytes(bytes);
            BedrockGamePacket gamePacket = new BedrockGamePacket(
                    new BedrockPacketHeader(packetId, 0, 0), bytes);
            return sendGamePacket(connection, gamePacket) == TransportSendResult.ACCEPTED;
        } catch (Exception failure) {
            log.accept("Could not encode Bedrock response packet: " + failure.getMessage());
            connection.close();
            return false;
        } finally {
            payload.release();
        }
    }

    private TransportSendResult sendGamePacket(TransportConnection connection, BedrockGamePacket packet) {
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(packet));
        ByteBuffer frame = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, networkSettings);
        return connection.send(frame, DeliveryMode.RELIABLE_ORDERED);
    }

    private void removeSession(PlayerSession session) {
        if (!byConnection.remove(session.connection.id(), session)) return;
        byPlayer.remove(session.playerId, session);
        session.visibleItemEntities.clear();
        if (session.entitySpawned) {
            entities.remove(session.playerId);
            chunkStreaming.removePlayer(session.playerId);
            session.entitySpawned = false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (PlayerSession session : new ArrayList<>(byConnection.values())) {
            session.closed.set(true);
            removeSession(session);
        }
        byConnection.clear();
        byPlayer.clear();
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    private static double normalizeYaw(double yaw) {
        double normalized = yaw % 360.0d;
        if (normalized > 180.0d) normalized -= 360.0d;
        if (normalized < -180.0d) normalized += 360.0d;
        return normalized;
    }

    private static final class PlayerSession {
        private final TransportConnection connection;
        private final BedrockAuthenticatedPlayer player;
        private final UUID playerId;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<PlayerInput> pendingInput = new AtomicReference<>();
        private final ConcurrentLinkedQueue<BlockAction> pendingBlockActions = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pendingBlockActionCount = new AtomicInteger();
        private final AtomicInteger requestedRadius = new AtomicInteger(-1);
        private BlockPosition breakingBlock;
        private final Set<ChunkPosition> sentChunks = new HashSet<>();
        private final Set<UUID> visibleItemEntities = new HashSet<>();
        private double x;
        private double y;
        private double z;
        private double yaw;
        private boolean onGround = true;
        private boolean entitySpawned;
        private boolean inventorySynced;
        private boolean playReady;
        private long lastInputTick = -1;
        private int acknowledgedRadius = -1;
        private ChunkPosition lastPublishedCenter;

        private void enqueueBlockAction(BlockAction action) {
            int count = pendingBlockActionCount.incrementAndGet();
            if (count > 64) {
                pendingBlockActionCount.decrementAndGet();
                throw new IllegalArgumentException("Too many pending block actions");
            }
            pendingBlockActions.add(action);
        }

        private BlockAction pollBlockAction() {
            BlockAction action = pendingBlockActions.poll();
            if (action != null) pendingBlockActionCount.decrementAndGet();
            return action;
        }

        private PlayerSession(TransportConnection connection, BedrockAuthenticatedPlayer player,
                              UUID playerId, double x, double y, double z, double yaw) {
            this.connection = connection;
            this.player = player;
            this.playerId = playerId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    private record BlockAction(PlayerActionType type, BlockPosition position) {
    }

    private record PlayerInput(float x, float y, float z, float yaw,
                               float pitch, float headYaw, long tick, boolean onGround) {
        private static PlayerInput from(PlayerAuthInputPacket packet) {
            Vector3f position = packet.getPosition();
            Vector3f rotation = packet.getRotation();
            if (position == null || rotation == null) {
                return new PlayerInput(Float.NaN, Float.NaN, Float.NaN,
                        Float.NaN, Float.NaN, Float.NaN, packet.getTick(), packet.isOnGround());
            }
            return new PlayerInput(position.getX(), position.getY(), position.getZ(),
                    rotation.getY(), rotation.getX(), rotation.getZ(),
                    packet.getTick(), packet.isOnGround());
        }

        private boolean finite() {
            return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                    && Float.isFinite(yaw) && Float.isFinite(pitch) && Float.isFinite(headYaw);
        }
    }
}
