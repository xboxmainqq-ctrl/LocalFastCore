package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NexoraPrivateProtocolHandlerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void repliesToSupportedClientAndSignalsReady() {
        UUID serverId = UUID.randomUUID();
        AtomicInteger ready = new AtomicInteger();
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(serverId, 1024 * 1024,
                ignored -> ready.incrementAndGet()).accept(connection);

        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));

        assertEquals(1, ready.get());
        assertFalse(connection.closed);
        NexoraPrivateProtocolCodec.ServerResponse response =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(connection.sentMessages.get(0)));
        assertTrue(response.accepted());
        assertEquals(serverId, response.serverId());
        assertEquals(1024 * 1024, response.maxMessageBytes());
    }

    @Test
    void sendsWorldInfoImmediatelyAfterServerHello() {
        UUID serverId = UUID.randomUUID();
        NexoraWorldInfo expected = new NexoraWorldInfo(
                "world", -64, 320, 0, 65, 0, 20, 1);
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(serverId, 1024 * 1024, () -> expected,
                ignored -> { }).accept(connection);

        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));

        assertEquals(2, connection.sentMessages.size());
        NexoraPrivateProtocolCodec.ServerResponse hello =
                NexoraPrivateProtocolCodec.decodeServerResponse(
                        ByteBuffer.wrap(connection.sentMessages.get(0)));
        assertTrue(hello.accepted());
        assertEquals(serverId, hello.serverId());
        NexoraWorldInfo actual = NexoraPrivateProtocolCodec.decodeWorldInfo(
                ByteBuffer.wrap(connection.sentMessages.get(1)));
        assertEquals(expected, actual);
    }

    @Test
    void rejectsClientsOutsideTheNexoraAndroidBuildNamespace() {
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 1024,
                ignored -> fail("non-NEXORA client must not become ready")).accept(connection);

        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("minecraft-bedrock"));

        NexoraPrivateProtocolCodec.ServerResponse response =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(connection.sentMessages.get(0)));
        assertFalse(response.accepted());
        assertEquals(3, response.rejectionCode());
        assertEquals("This server requires the NEXORA client", response.rejectionReason());
        assertFalse(connection.closed);
    }

    @Test
    void rejectsUnsupportedMajorVersionDeterministically() {
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 1024, ignored -> fail("must not become ready"))
                .accept(connection);
        byte[] hello = NexoraPrivateProtocolCodec.encodeClientHello("android-dev");
        hello[4] = 2;

        connection.deliver(hello);

        NexoraPrivateProtocolCodec.ServerResponse response =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(connection.sentMessages.get(0)));
        assertFalse(response.accepted());
        assertEquals(1, response.rejectionCode());
    }

    @Test
    void rejectsOlderMinorVersionWhenInventoryWireFormatChanges() {
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 1024,
                ignored -> fail("old protocol must not become ready")).accept(connection);
        byte[] hello = NexoraPrivateProtocolCodec.encodeClientHello("android-dev");
        hello[5] = 1;

        connection.deliver(hello);

        NexoraPrivateProtocolCodec.ServerResponse response =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(connection.sentMessages.get(0)));
        assertFalse(response.accepted());
        assertEquals(1, response.rejectionCode());
    }

    @Test
    void rejectsMalformedHelloWithoutPromotingConnection() {
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 1024, ignored -> fail("must not become ready"))
                .accept(connection);

        connection.deliver(new byte[] {1, 2, 3});

        NexoraPrivateProtocolCodec.ServerResponse response =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(connection.sentMessages.get(0)));
        assertFalse(response.accepted());
        assertEquals(2, response.rejectionCode());
    }


    @Test
    void servesRequestedChunkVolumeAfterHandshake() {
        UUID serverId = UUID.randomUUID();
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 64, 0), new BlockState("minecraft:grass_block"));
        world.setBlock(new BlockPosition(1, 63, 0), new BlockState("minecraft:stone"));
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        new NexoraPrivateProtocolHandler(serverId, 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { }).accept(connection);

        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));
        connection.deliver(NexoraChunkDataCodec.encodeChunkRequest(0, 0));

        assertEquals(5, connection.sentMessages.size());
        NexoraChunkData chunk = NexoraChunkDataCodec.decodeChunkData(
                ByteBuffer.wrap(connection.sentMessages.get(4)));
        assertEquals(0, chunk.chunkX());
        assertEquals(0, chunk.chunkZ());
        assertEquals(2, chunk.blocks().size());
        assertEquals("minecraft:stone", chunk.blocks().get(0).blockIdentifier());
        assertEquals("minecraft:grass_block", chunk.blocks().get(1).blockIdentifier());
        assertEquals(64, chunk.blocks().get(1).y());
    }



    @Test
    void generatesAndStreamsAnUnloadedNeighborChunk() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(-64, 320));
        FlatChunkGenerator generator = new FlatChunkGenerator(64, 5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", -64, 320, 0, 65, 0, 20, 0);
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world,
                position -> world.isChunkLoaded(position)
                        || world.loadChunk(generator.generate(position, world.height(), 42L)),
                ignored -> { }).accept(connection);
        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));

        connection.deliver(NexoraChunkDataCodec.encodeChunkRequest(1, 1));

        assertTrue(world.isChunkLoaded(new ChunkPosition(1, 1)));
        NexoraChunkData chunk = NexoraChunkDataCodec.decodeChunkData(
                ByteBuffer.wrap(connection.sentMessages.get(4)));
        assertEquals(1536, chunk.blocks().size());
        assertEquals("minecraft:bedrock", chunk.blocks().get(0).blockIdentifier());
        assertEquals("minecraft:grass_block",
                chunk.blocks().get(chunk.blocks().size() - 1).blockIdentifier());
    }

    @Test
    void validatesMovementAndReturnsAuthoritativePosition() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FlatChunkGenerator generator = new FlatChunkGenerator(64, 5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));
        world.loadChunk(generator.generate(new ChunkPosition(0, 0), world.height(), 9L));
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { }).accept(connection);
        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));

        assertEquals(4, connection.sentMessages.size());
        assertEquals(new NexoraMovementValidator.Position(0.5, 65.0, 0.5),
                NexoraMovementPacketCodec.decodeServerPosition(
                        ByteBuffer.wrap(connection.sentMessages.get(2))));

        NexoraMovementValidator.Position proposed =
                new NexoraMovementValidator.Position(0.7, 65.0, 0.5);
        connection.deliver(NexoraMovementPacketCodec.encodeClientMove(proposed));

        assertEquals(5, connection.sentMessages.size());
        NexoraMovementValidator.Position accepted = NexoraMovementPacketCodec.decodeServerPosition(
                ByteBuffer.wrap(connection.sentMessages.get(4)));
        assertEquals(proposed, accepted);
    }

    @Test
    void acceptedPlayerMovementAutomaticallyCollectsNearbyDroppedItems() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FlatChunkGenerator generator = new FlatChunkGenerator(64, 5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));
        world.loadChunk(generator.generate(new ChunkPosition(0, 0), world.height(), 9L));
        EntityRegistry entities = new EntityRegistry();
        java.util.Map<UUID, PlayerInventory> inventories = new java.util.concurrent.ConcurrentHashMap<>();
        UUID itemId = UUID.randomUUID();
        entities.spawn(new WorldEntity(itemId, WorldEntity.Type.ITEM,
                "minecraft:apple", 1.2, 65.0, 0.5, 0, -1));
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        NexoraPrivateProtocolHandler handler = new NexoraPrivateProtocolHandler(
                UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { }, entities, inventories);
        handler.accept(connection);
        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev", "Picker", 0));

        UUID playerId = entities.snapshot().stream()
                .filter(entity -> entity.type() == WorldEntity.Type.PLAYER)
                .findFirst().orElseThrow().id();
        assertEquals(2, entities.size());

        connection.deliver(NexoraMovementPacketCodec.encodeClientMove(
                new NexoraMovementValidator.Position(0.7, 65.0, 0.5)));

        assertTrue(entities.find(itemId).isEmpty());
        assertEquals(1, handler.inventory(playerId).orElseThrow().count("minecraft:apple"));
        assertEquals(1, inventories.get(playerId).count("minecraft:apple"));
        NexoraInventorySyncCodec.InventoryState initial =
                NexoraInventorySyncCodec.decode(ByteBuffer.wrap(connection.sentMessages.get(3)));
        assertTrue(initial.stacks().isEmpty());
        NexoraInventorySyncCodec.InventoryState updated =
                NexoraInventorySyncCodec.decode(ByteBuffer.wrap(connection.sentMessages.get(4)));
        assertEquals(playerId, updated.playerId());
        assertEquals(1, updated.stacks().size());
        assertEquals(new NexoraInventorySyncCodec.InventoryEntry(0,
                new ItemStack("minecraft:apple", 1)), updated.stacks().get(0));
    }

    @Test
    void movesAndDropsInventoryItemsOnlyThroughServerActions() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FlatChunkGenerator generator = new FlatChunkGenerator(64, 5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));
        world.loadChunk(generator.generate(new ChunkPosition(0, 0), world.height(), 9L));
        EntityRegistry entities = new EntityRegistry();
        java.util.Map<UUID, PlayerInventory> inventories = new java.util.concurrent.ConcurrentHashMap<>();
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        NexoraPrivateProtocolHandler handler = new NexoraPrivateProtocolHandler(
                UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { }, entities, inventories);
        handler.accept(connection);
        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev", "Mover", 0));
        UUID playerId = entities.snapshot().stream()
                .filter(entity -> entity.type() == WorldEntity.Type.PLAYER)
                .findFirst().orElseThrow().id();
        PlayerInventory inventory = handler.inventory(playerId).orElseThrow();
        assertTrue(inventory.addOne("minecraft:apple"));
        assertTrue(inventory.addOne("minecraft:apple"));

        connection.deliver(NexoraInventoryActionCodec.encodeRequest(
                NexoraInventoryActionCodec.Action.MOVE_OR_SWAP, 0, 5));

        assertNull(inventory.stackAt(0));
        assertEquals(new ItemStack("minecraft:apple", 2), inventory.stackAt(5));
        assertEquals(13, Byte.toUnsignedInt(connection.sentMessages.get(4)[6]));
        NexoraInventorySyncCodec.InventoryState moved =
                NexoraInventorySyncCodec.decode(ByteBuffer.wrap(connection.sentMessages.get(5)));
        assertEquals(5, moved.stacks().get(0).slot());

        connection.deliver(NexoraInventoryActionCodec.encodeRequest(
                NexoraInventoryActionCodec.Action.DROP_ONE, 5, 0));

        assertEquals(new ItemStack("minecraft:apple", 1), inventory.stackAt(5));
        assertEquals(1L, entities.snapshot().stream()
                .filter(entity -> entity.type() == WorldEntity.Type.ITEM
                        && entity.name().equals("minecraft:apple")).count());
        assertEquals(13, Byte.toUnsignedInt(connection.sentMessages.get(6)[6]));

        connection.deliver(NexoraInventoryActionCodec.encodeRequest(
                NexoraInventoryActionCodec.Action.USE, 5, 0));

        assertEquals(new ItemStack("minecraft:apple", 1), inventory.stackAt(5),
                "Unsupported use must not consume the item");
        assertEquals(3, Byte.toUnsignedInt(connection.sentMessages.get(8)[8]),
                "Server must report NOT_USABLE until item behavior exists");
    }

    @Test
    void restoresInventoryWhenPlayerReconnectsWithSameName() throws Exception {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FlatChunkGenerator generator = new FlatChunkGenerator(64, 5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));
        world.loadChunk(generator.generate(new ChunkPosition(0, 0), world.height(), 9L));
        EntityRegistry entities = new EntityRegistry();
        java.util.Map<UUID, PlayerInventory> inventories =
                new java.util.concurrent.ConcurrentHashMap<>();
        FilePlayerInventoryStorage storage =
                new FilePlayerInventoryStorage(temporaryDirectory.resolve("players"));
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        NexoraPrivateProtocolHandler handler = new NexoraPrivateProtocolHandler(
                UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { }, entities, inventories, storage);

        FakeConnection firstConnection = new FakeConnection();
        handler.accept(firstConnection);
        firstConnection.deliver(NexoraPrivateProtocolCodec.encodeClientHello(
                "android-dev", "PersistentPlayer", 0));
        UUID firstPlayerId = entities.snapshot().stream()
                .filter(entity -> entity.type() == WorldEntity.Type.PLAYER)
                .findFirst().orElseThrow().id();
        PlayerInventory firstInventory = handler.inventory(firstPlayerId).orElseThrow();
        assertTrue(firstInventory.addOne("minecraft:diamond"));
        firstConnection.close();

        assertTrue(inventories.isEmpty(), "Saved inventory should leave the active-session map");

        EntityRegistry restartedEntities = new EntityRegistry();
        java.util.Map<UUID, PlayerInventory> restartedInventories =
                new java.util.concurrent.ConcurrentHashMap<>();
        NexoraPrivateProtocolHandler restartedHandler = new NexoraPrivateProtocolHandler(
                UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { },
                restartedEntities, restartedInventories, storage);
        FakeConnection secondConnection = new FakeConnection();
        restartedHandler.accept(secondConnection);
        secondConnection.deliver(NexoraPrivateProtocolCodec.encodeClientHello(
                "android-dev", "PersistentPlayer", 0));
        UUID secondPlayerId = restartedEntities.snapshot().stream()
                .filter(entity -> entity.type() == WorldEntity.Type.PLAYER)
                .findFirst().orElseThrow().id();

        assertEquals(firstPlayerId, secondPlayerId);
        PlayerInventory restored = restartedHandler.inventory(secondPlayerId).orElseThrow();
        assertEquals(new ItemStack("minecraft:diamond", 1), restored.stackAt(0));
    }

    @Test
    void rejectsMovementThroughBlockAndReturnsPreviousPosition() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FlatChunkGenerator generator = new FlatChunkGenerator(64, 5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));
        world.loadChunk(generator.generate(new ChunkPosition(0, 0), world.height(), 9L));
        world.setBlock(new BlockPosition(1, 66, 0), new BlockState("minecraft:stone"));
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { }).accept(connection);
        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));

        connection.deliver(NexoraMovementPacketCodec.encodeClientMove(
                new NexoraMovementValidator.Position(0.7, 65.0, 0.5)));

        NexoraMovementValidator.Position corrected = NexoraMovementPacketCodec.decodeServerPosition(
                ByteBuffer.wrap(connection.sentMessages.get(4)));
        assertEquals(new NexoraMovementValidator.Position(0.5, 65.0, 0.5), corrected);
        assertFalse(connection.closed);
    }

    @Test
    void synchronizesRemotePlayersJoinMoveAndLeave() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FlatChunkGenerator generator = new FlatChunkGenerator(64, 5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));
        world.loadChunk(generator.generate(new ChunkPosition(0, 0), world.height(), 9L));
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        NexoraPrivateProtocolHandler handler = new NexoraPrivateProtocolHandler(
                UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { });
        FakeConnection first = new FakeConnection();
        FakeConnection second = new FakeConnection();

        handler.accept(first);
        first.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev", "Первый", 2));
        handler.accept(second);
        second.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev", "Второй", 4));

        assertEquals(5, first.sentMessages.size());
        assertEquals(5, second.sentMessages.size());
        assertEquals(2, handler.entities().size());
        NexoraPlayerSyncCodec.PlayerState firstSeenBySecond =
                NexoraPlayerSyncCodec.decodePlayerState(ByteBuffer.wrap(second.sentMessages.get(3)));
        NexoraPlayerSyncCodec.PlayerState secondSeenByFirst =
                NexoraPlayerSyncCodec.decodePlayerState(ByteBuffer.wrap(first.sentMessages.get(4)));
        assertNotEquals(firstSeenBySecond.playerId(), secondSeenByFirst.playerId());
        assertEquals(2, firstSeenBySecond.skinIndex());
        assertEquals(4, secondSeenByFirst.skinIndex());
        WorldEntity firstEntity = handler.entities().find(firstSeenBySecond.playerId()).orElseThrow();
        assertEquals(WorldEntity.Type.PLAYER, firstEntity.type());
        assertEquals("Первый", firstEntity.name());
        assertEquals(2, firstEntity.skinIndex());
        assertEquals(0.5, firstEntity.x());

        first.deliver(NexoraMovementPacketCodec.encodeClientMove(
                new NexoraMovementValidator.Position(0.7, 65.0, 0.5)));

        assertEquals(6, second.sentMessages.size());
        NexoraPlayerSyncCodec.PlayerState moved =
                NexoraPlayerSyncCodec.decodePlayerState(ByteBuffer.wrap(second.sentMessages.get(5)));
        assertEquals(firstSeenBySecond.playerId(), moved.playerId());
        assertEquals(0.7, moved.x(), 0.0001);
        assertEquals(0.7, handler.entities().find(firstSeenBySecond.playerId()).orElseThrow().x(), 0.0001);

        first.close();

        assertEquals(7, second.sentMessages.size());
        assertEquals(firstSeenBySecond.playerId(),
                NexoraPlayerSyncCodec.decodePlayerLeft(ByteBuffer.wrap(second.sentMessages.get(6))));
        assertTrue(handler.entities().find(firstSeenBySecond.playerId()).isEmpty());
        assertEquals(1, handler.entities().size());
    }

    @Test
    void doesNotReplicatePlayersOutsideVisibilityRadius() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        AtomicInteger worldInfoRequests = new AtomicInteger();
        NexoraPrivateProtocolHandler handler = new NexoraPrivateProtocolHandler(
                UUID.randomUUID(), 8 * 1024 * 1024,
                () -> {
                    int index = worldInfoRequests.getAndIncrement();
                    int spawnX = index == 0 ? 0 : 200;
                    return new NexoraWorldInfo("world", 0, 128, spawnX, 65, 0, 20, 1);
                },
                () -> world, ignored -> true, ignored -> { });
        FakeConnection first = new FakeConnection();
        FakeConnection distant = new FakeConnection();

        handler.accept(first);
        first.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev", "Ближний", 0));
        handler.accept(distant);
        distant.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev", "Дальний", 1));

        assertEquals(2, handler.entities().size());
        assertEquals(4, first.sentMessages.size(),
                "The first client should not receive a distant player's state");
        assertEquals(4, distant.sentMessages.size(),
                "The distant client should not receive the first player's state");

        distant.close();

        assertEquals(4, first.sentMessages.size(),
                "A client that never saw the distant player should not receive a leave packet");
        assertEquals(1, handler.entities().size());
    }

    @Test
    void refusesChunkRequestsOutsideSpawnNeighborhood() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { }).accept(connection);
        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));

        connection.deliver(NexoraChunkDataCodec.encodeChunkRequest(2, 0));

        assertTrue(connection.closed);
    }

    @Test
    void allowsRepeatedChunkRequestsWithoutClosingConnection() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> true, ignored -> { }).accept(connection);
        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));
        for (int i = 0; i < 10; i++) {
            connection.deliver(NexoraChunkDataCodec.encodeChunkRequest(0, 0));
            if (connection.closed) break;
        }

        assertFalse(connection.closed);
        assertEquals(14, connection.sentMessages.size());
    }

    private static final class FakeConnection implements TransportConnection {
        private Consumer<TransportMessage> messageListener;
        private Consumer<TransportCloseEvent> closeListener;
        private final List<byte[]> sentMessages = new ArrayList<>();
        private boolean closed;

        void deliver(byte[] bytes) {
            messageListener.accept(new TransportMessage(
                    ByteBuffer.wrap(bytes), DeliveryMode.RELIABLE_ORDERED));
        }

        @Override public String id() { return "test-peer"; }
        @Override public boolean isOpen() { return !closed; }
        @Override public void setMessageListener(Consumer<TransportMessage> listener) {
            messageListener = listener;
        }
        @Override public void setCloseListener(Consumer<TransportCloseEvent> listener) {
            closeListener = listener;
        }
        @Override public TransportSendResult send(ByteBuffer payload, DeliveryMode mode) {
            ByteBuffer copy = payload.duplicate();
            byte[] sent = new byte[copy.remaining()];
            copy.get(sent);
            sentMessages.add(sent);
            return closed ? TransportSendResult.CLOSED : TransportSendResult.ACCEPTED;
        }
        @Override public void close() {
            if (!closed) {
                closed = true;
                if (closeListener != null) closeListener.accept(new TransportCloseEvent("closed", false));
            }
        }
    }
}
