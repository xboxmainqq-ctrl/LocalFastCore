package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockWorldStartupHandlerTest {
    private static final BedrockNetworkSettings NETWORK = new BedrockNetworkSettings(
            0, BedrockNetworkSettings.CompressionAlgorithm.NONE, false, 0, 0.0f);

    @Test
    void sendsStartGameBeforeSkeletonChunksAsOneReliableBatch() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), Map.of()));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(1, 0), Map.of()));
        FakeConnection connection = new FakeConnection(TransportSendResult.ACCEPTED);
        BedrockWorldStartupHandler startup = new BedrockWorldStartupHandler(
                NETWORK, world, 0, -1,
                List.of(new ChunkPosition(0, 0), new ChunkPosition(1, 0)),
                registryProfile(false), (peer, player) -> settings(0, false));

        startup.start(connection, player()).toCompletableFuture().join();

        assertEquals(1, connection.sent.size());
        assertEquals(DeliveryMode.RELIABLE_ORDERED, connection.lastDeliveryMode);
        ByteBuffer batch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                connection.sent.get(0), NETWORK);
        List<BedrockGamePacket> packets = BedrockPacketBatchCodec.decodeUncompressed(batch);
        assertEquals(List.of(11, 162, 58, 58),
                packets.stream().map(packet -> packet.header().packetId()).toList());

        ByteBuffer firstSkeleton = packets.get(2).payloadBuffer();
        assertEquals(0, BedrockBinary.readVarInt32(firstSkeleton));
        assertEquals(0, BedrockBinary.readVarInt32(firstSkeleton));
        assertEquals(0, BedrockBinary.readVarInt32(firstSkeleton));
        assertEquals(-1, BedrockBinary.readVarUInt32(firstSkeleton));
        assertEquals(1, Byte.toUnsignedInt(firstSkeleton.get())); // request limit present
        assertEquals(-1, BedrockBinary.readVarInt32(firstSkeleton));
        assertEquals(0, Byte.toUnsignedInt(firstSkeleton.get())); // cache disabled
    }


    @Test
    void startsWithComputedHashProfileWhenStartGameAdvertisesHashes() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), Map.of()));
        FakeConnection connection = new FakeConnection(TransportSendResult.ACCEPTED);
        BedrockWorldStartupHandler startup = new BedrockWorldStartupHandler(
                NETWORK, world, 0, -1, List.of(new ChunkPosition(0, 0)),
                BedrockRuntimeBlockRegistryProfile.hashing(),
                (peer, player) -> settings(0, true));

        startup.start(connection, player()).toCompletableFuture().join();

        assertEquals(1, connection.sent.size());
        assertTrue(connection.isOpen());
    }

    @Test
    void refusesToStartBeforeSpawnChunksAreLoaded() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        FakeConnection connection = new FakeConnection(TransportSendResult.ACCEPTED);
        BedrockWorldStartupHandler startup = new BedrockWorldStartupHandler(
                NETWORK, world, 0, -1, List.of(new ChunkPosition(0, 0)),
                registryProfile(false), (peer, player) -> settings(0, false));

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> startup.start(connection, player()).toCompletableFuture().join());
        assertTrue(connection.sent.isEmpty());
    }

    @Test
    void rejectsStartGameDimensionMismatchAndTransportBackpressure() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), Map.of()));
        FakeConnection mismatchConnection = new FakeConnection(TransportSendResult.ACCEPTED);
        BedrockWorldStartupHandler mismatch = new BedrockWorldStartupHandler(
                NETWORK, world, 1, -1, List.of(new ChunkPosition(0, 0)),
                registryProfile(false), (peer, player) -> settings(0, false));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> mismatch.start(mismatchConnection, player()).toCompletableFuture().join());
        assertTrue(mismatchConnection.sent.isEmpty());

        FakeConnection backpressureConnection = new FakeConnection(TransportSendResult.BACKPRESSURE);
        BedrockWorldStartupHandler backpressure = new BedrockWorldStartupHandler(
                NETWORK, world, 0, -1, List.of(new ChunkPosition(0, 0)),
                registryProfile(false), (peer, player) -> settings(0, false));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> backpressure.start(backpressureConnection, player()).toCompletableFuture().join());
        assertTrue(backpressureConnection.sent.isEmpty());

        FakeConnection modeMismatchConnection = new FakeConnection(TransportSendResult.ACCEPTED);
        BedrockWorldStartupHandler modeMismatch = new BedrockWorldStartupHandler(
                NETWORK, world, 0, -1, List.of(new ChunkPosition(0, 0)),
                registryProfile(true), (peer, player) -> settings(0, false));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> modeMismatch.start(modeMismatchConnection, player()).toCompletableFuture().join());
        assertTrue(modeMismatchConnection.sent.isEmpty());
    }


    private static BedrockRuntimeBlockRegistryProfile registryProfile(boolean hashes) {
        return BedrockRuntimeBlockRegistryProfile.from(
                new BedrockRuntimeBlockRegistryFile.Loaded(
                        2225, "1.26.60",
                        hashes ? BedrockRuntimeBlockRegistryFile.IdMode.HASH
                                : BedrockRuntimeBlockRegistryFile.IdMode.RUNTIME,
                        "0".repeat(64), Map.of(BlockState.AIR, 0)));
    }

    private static BedrockStartGamePacketCodec.Settings settings(int dimension, boolean hashes) {
        var level = new BedrockLevelSettingsCodec.Settings(
                123L, dimension,
                new BedrockStartGamePrimitivesCodec.BlockPosition(0, 64, 0),
                2, -1, 4, "1.26.60");
        return new BedrockStartGamePacketCodec.Settings(
                1L, 1L, 0,
                new BedrockStartGamePrimitivesCodec.Vec3(0, 65, 0),
                new BedrockStartGamePrimitivesCodec.Vec2(0, 0),
                level, "nexora-world", "NEXORA", "", false,
                new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(0, false),
                6000L, 0, "test-correlation", false, "NEXORA/0.1",
                BedrockStartGamePacketCodec.PlayerPropertyData.empty(),
                0, 0, 0, false, hashes, false,
                BedrockStartGamePacketCodec.Telemetry.empty());
    }

    private static BedrockAuthenticatedPlayer player() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
        return new BedrockAuthenticatedPlayer("test-xuid", "NEXORA", key);
    }

    private static final class FakeConnection implements TransportConnection {
        private final List<ByteBuffer> sent = new ArrayList<>();
        private final TransportSendResult sendResult;
        private DeliveryMode lastDeliveryMode;
        private boolean open = true;

        private FakeConnection(TransportSendResult sendResult) {
            this.sendResult = sendResult;
        }

        @Override public String id() { return "world-startup-peer"; }
        @Override public boolean isOpen() { return open; }
        @Override public void setMessageListener(Consumer<TransportMessage> listener) { }
        @Override public void setCloseListener(Consumer<TransportCloseEvent> listener) { }
        @Override public TransportSendResult send(ByteBuffer payload, DeliveryMode mode) {
            lastDeliveryMode = mode;
            if (!open) return TransportSendResult.CLOSED;
            if (sendResult != TransportSendResult.ACCEPTED) return sendResult;
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate()).flip();
            sent.add(copy.asReadOnlyBuffer());
            return TransportSendResult.ACCEPTED;
        }
        @Override public void close() { open = false; }
    }
}
