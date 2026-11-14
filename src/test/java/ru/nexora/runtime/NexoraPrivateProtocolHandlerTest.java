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
    void servesRequestedChunkSurfaceAfterHandshake() {
        UUID serverId = UUID.randomUUID();
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 64, 0), new BlockState("minecraft:grass_block"));
        world.setBlock(new BlockPosition(1, 63, 0), new BlockState("minecraft:stone"));
        FakeConnection connection = new FakeConnection();
        NexoraWorldInfo info = new NexoraWorldInfo("world", 0, 128, 0, 65, 0, 20, 1);
        new NexoraPrivateProtocolHandler(serverId, 8 * 1024 * 1024, () -> info,
                () -> world, ignored -> { }).accept(connection);

        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));
        connection.deliver(NexoraChunkDataCodec.encodeChunkRequest(0, 0));

        assertEquals(3, connection.sentMessages.size());
        NexoraChunkData chunk = NexoraChunkDataCodec.decodeChunkData(
                ByteBuffer.wrap(connection.sentMessages.get(2)));
        assertEquals(0, chunk.chunkX());
        assertEquals(0, chunk.chunkZ());
        assertEquals(2, chunk.columns().size());
        assertEquals("minecraft:grass_block", chunk.columns().get(0).blockIdentifier());
        assertEquals(64, chunk.columns().get(0).y());
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
