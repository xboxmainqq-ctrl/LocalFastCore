package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NexoraPrivateProtocolHandlerTest {
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

        assertEquals(3, connection.sentMessages.size());
        NexoraChunkData chunk = NexoraChunkDataCodec.decodeChunkData(
                ByteBuffer.wrap(connection.sentMessages.get(2)));
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
                ByteBuffer.wrap(connection.sentMessages.get(2)));
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

        NexoraMovementValidator.Position proposed =
                new NexoraMovementValidator.Position(0.7, 65.0, 0.5);
        connection.deliver(NexoraMovementPacketCodec.encodeClientMove(proposed));

        assertEquals(3, connection.sentMessages.size());
        NexoraMovementValidator.Position accepted = NexoraMovementPacketCodec.decodeServerPosition(
                ByteBuffer.wrap(connection.sentMessages.get(2)));
        assertEquals(proposed, accepted);
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
                new NexoraMovementValidator.Position(1.2, 65.0, 0.5)));

        NexoraMovementValidator.Position corrected = NexoraMovementPacketCodec.decodeServerPosition(
                ByteBuffer.wrap(connection.sentMessages.get(2)));
        assertEquals(new NexoraMovementValidator.Position(0.5, 65.0, 0.5), corrected);
        assertFalse(connection.closed);
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
    void refusesMoreThanNineChunkRequestsPerConnection() {
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

        assertTrue(connection.closed);
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
