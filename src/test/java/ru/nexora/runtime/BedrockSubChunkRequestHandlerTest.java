package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSubChunkRequestHandlerTest {
    private static final BedrockNetworkSettings SETTINGS = new BedrockNetworkSettings(
            0, BedrockNetworkSettings.CompressionAlgorithm.NONE, false, 0, 0.0f);
    private static final BlockState STONE = new BlockState("minecraft:stone");

    @Test
    void routesReliableSubchunkRequestToWorldAndSendsPacket174() {
        InMemoryWorld world = new InMemoryWorld("test", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0),
                Map.of(new BlockPosition(0, 0, 0), STONE)));
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0, STONE, 1));
        AtomicInteger fallbackCalls = new AtomicInteger();
        BedrockSubChunkRequestHandler handler = new BedrockSubChunkRequestHandler(
                SETTINGS, world, ignored -> 0, blockProfile(),
                (connection, message) -> fallbackCalls.incrementAndGet());
        FakeConnection connection = new FakeConnection();

        handler.accept(connection, subchunkRequest(DeliveryMode.RELIABLE_ORDERED));

        assertTrue(connection.isOpen());
        assertEquals(1, connection.sent.size());
        assertEquals(0, fallbackCalls.get());
        ByteBuffer batch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                connection.sent.get(0), SETTINGS);
        List<BedrockGamePacket> packets = BedrockPacketBatchCodec.decodeUncompressed(batch);
        assertEquals(1, packets.size());
        assertEquals(174, packets.get(0).header().packetId());
    }

    @Test
    void delegatesOtherGamePacketsWithoutClosingConnection() {
        InMemoryWorld world = new InMemoryWorld("test", new WorldHeight(-64, 320));
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));
        AtomicInteger fallbackCalls = new AtomicInteger();
        BedrockSubChunkRequestHandler handler = new BedrockSubChunkRequestHandler(
                SETTINGS, world, ignored -> 0, registry,
                (connection, message) -> fallbackCalls.incrementAndGet());
        FakeConnection connection = new FakeConnection();
        ByteBuffer frame = encode(new BedrockGamePacket(
                new BedrockPacketHeader(1, 0, 0), new byte[] {1, 2, 3}));

        handler.accept(connection, new TransportMessage(frame, DeliveryMode.RELIABLE_ORDERED));

        assertTrue(connection.isOpen());
        assertEquals(1, fallbackCalls.get());
        assertTrue(connection.sent.isEmpty());
    }


    @Test
    void splitsMixedBatchesAndForwardsNonSubchunkPackets() {
        InMemoryWorld world = new InMemoryWorld("test", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0),
                Map.of(new BlockPosition(0, 0, 0), STONE)));
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0, STONE, 1));
        AtomicInteger fallbackCalls = new AtomicInteger();
        AtomicInteger forwardedPacketId = new AtomicInteger(-1);
        BedrockSubChunkRequestHandler handler = new BedrockSubChunkRequestHandler(
                SETTINGS, world, ignored -> 0, registry, (connection, message) -> {
                    fallbackCalls.incrementAndGet();
                    ByteBuffer decoded = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                            message.payload(), SETTINGS);
                    List<BedrockGamePacket> forwarded = BedrockPacketBatchCodec.decodeUncompressed(decoded);
                    assertEquals(1, forwarded.size());
                    forwardedPacketId.set(forwarded.get(0).header().packetId());
                });
        FakeConnection connection = new FakeConnection();

        ByteBuffer requestPayload = ByteBuffer.allocate(32);
        BedrockBinary.writeVarInt32(requestPayload, 0);
        BedrockBinary.writeVarUInt32(requestPayload, 1);
        requestPayload.put((byte) 0).put((byte) 0).put((byte) 0);
        BedrockBinary.writeLittleEndianInt32(requestPayload, 0);
        BedrockBinary.writeLittleEndianInt32(requestPayload, 0);
        BedrockBinary.writeLittleEndianInt32(requestPayload, 0);
        requestPayload.flip();
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(175, 0, 0), bytes(requestPayload)),
                new BedrockGamePacket(new BedrockPacketHeader(1, 0, 0), new byte[] {9})
        ));

        handler.accept(connection, new TransportMessage(
                BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, SETTINGS),
                DeliveryMode.RELIABLE_ORDERED));

        assertTrue(connection.isOpen());
        assertEquals(1, connection.sent.size());
        assertEquals(1, fallbackCalls.get());
        assertEquals(1, forwardedPacketId.get());
    }

    @Test
    void closesOnUnreliableOrMalformedSubchunkRequests() {
        InMemoryWorld world = new InMemoryWorld("test", new WorldHeight(-64, 320));
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));
        BedrockSubChunkRequestHandler handler = new BedrockSubChunkRequestHandler(
                SETTINGS, world, ignored -> 0, registry, (connection, message) -> { });

        FakeConnection unreliable = new FakeConnection();
        handler.accept(unreliable, subchunkRequest(DeliveryMode.UNRELIABLE));
        assertFalse(unreliable.isOpen());

        FakeConnection malformed = new FakeConnection();
        ByteBuffer malformedPayload = ByteBuffer.allocate(1);
        malformedPayload.put((byte) 0).flip();
        ByteBuffer malformedBatch = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(175, 0, 0), bytes(malformedPayload))));
        handler.accept(malformed, new TransportMessage(
                BedrockBatchCompressionCodec.encodeAfterNetworkSettings(malformedBatch, SETTINGS),
                DeliveryMode.RELIABLE_ORDERED));
        assertFalse(malformed.isOpen());
    }


    private static BedrockRuntimeBlockRegistryFile.Loaded blockProfile() {
        return new BedrockRuntimeBlockRegistryFile.Loaded(
                2225, "1.26.60", BedrockRuntimeBlockRegistryFile.IdMode.RUNTIME,
                "0".repeat(64), Map.of(BlockState.AIR, 0, STONE, 1));
    }

    private static TransportMessage subchunkRequest(DeliveryMode mode) {
        ByteBuffer payload = ByteBuffer.allocate(32);
        BedrockBinary.writeVarInt32(payload, 0);
        BedrockBinary.writeVarUInt32(payload, 1);
        payload.put((byte) 0).put((byte) 0).put((byte) 0);
        BedrockBinary.writeLittleEndianInt32(payload, 0);
        BedrockBinary.writeLittleEndianInt32(payload, 0);
        BedrockBinary.writeLittleEndianInt32(payload, 0);
        payload.flip();
        return new TransportMessage(encode(new BedrockGamePacket(
                new BedrockPacketHeader(175, 0, 0), bytes(payload))), mode);
    }

    private static ByteBuffer encode(BedrockGamePacket packet) {
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(packet));
        return BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, SETTINGS);
    }

    private static byte[] bytes(ByteBuffer source) {
        byte[] result = new byte[source.remaining()];
        source.get(result);
        return result;
    }

    private static final class FakeConnection implements TransportConnection {
        private final List<ByteBuffer> sent = new ArrayList<>();
        private boolean open = true;
        @Override public String id() { return "subchunk-peer"; }
        @Override public boolean isOpen() { return open; }
        @Override public void setMessageListener(Consumer<TransportMessage> listener) { }
        @Override public void setCloseListener(Consumer<TransportCloseEvent> listener) { }
        @Override public TransportSendResult send(ByteBuffer payload, DeliveryMode mode) {
            if (!open) return TransportSendResult.CLOSED;
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate()).flip();
            sent.add(copy.asReadOnlyBuffer());
            return TransportSendResult.ACCEPTED;
        }
        @Override public void close() { open = false; }
    }
}
