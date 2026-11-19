package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetInboundPipeline;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSubChunkRequestIntegrationTest {
    @Test
    void servesOnlySubChunksInsideTheAdvertisedChunkSet() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        World world = new World("subchunk-request-test", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0); // The first requested chunk was actually published and is still resident.
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world,
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            manager.onIdentity("viewer", new NetherNetClientIdentity(
                    "viewer-token", new byte[] {1, 2, 3}, "viewer-xuid",
                    "Viewer", "viewer-playfab", "20CA2", null));
            Object session = session(manager, "viewer");
            set(session, "loginAuthenticated", true);
            set(session, "gameStarted", true);
            set(session, "positionInitialized", true);
            set(session, "compressionNegotiated", true);
            sentChunks(session).add(chunkKey(0, 0));

            BedrockPacket request = request(0, 0, 0, 0,
                    new BedrockSubChunkCodec.Offset(0, 0, 0),
                    new BedrockSubChunkCodec.Offset(2, 0, 0));
            NetherNetInboundPipeline.InboundMessage message =
                    new NetherNetInboundPipeline.InboundMessage("viewer", "reliable", true, new byte[0]);
            Method handle = BedrockSessionManager.class.getDeclaredMethod(
                    "handleSubChunkRequest", NetherNetInboundPipeline.InboundMessage.class,
                    session.getClass(), BedrockPacket.class);
            handle.setAccessible(true);
            handle.invoke(manager, message, session, request);

            List<BedrockPacket> packets = packetsFor(outbound, "viewer");
            assertEquals(1, packets.size());
            BedrockPacket response = packets.getFirst();
            assertEquals(BedrockSubChunkCodec.SUB_CHUNK_PACKET_ID, response.packetId());

            ResponseReader reader = new ResponseReader(response.payload());
            assertFalse(reader.bool()); // blob cache disabled
            assertEquals(0, reader.varInt32());
            assertEquals(0, reader.int32());
            assertEquals(0, reader.int32());
            assertEquals(0, reader.int32());
            assertEquals(2, reader.varUInt32());

            assertEquals(new BedrockSubChunkCodec.Offset(0, 0, 0), reader.offset());
            int loadedResult = reader.byteValue();
            assertTrue(loadedResult == BedrockSubChunkCodec.RESULT_SUCCESS
                    || loadedResult == BedrockSubChunkCodec.RESULT_SUCCESS_ALL_AIR);
            if (loadedResult == BedrockSubChunkCodec.RESULT_SUCCESS) reader.byteArray();
            assertEquals(0, reader.byteValue()); // heightmap
            assertEquals(0, reader.byteValue()); // render heightmap

            assertEquals(new BedrockSubChunkCodec.Offset(2, 0, 0), reader.offset());
            assertEquals(BedrockSubChunkCodec.RESULT_LEVEL_CHUNK_DOES_NOT_EXIST, reader.byteValue());
            assertEquals(0, reader.byteValue());
            assertEquals(0, reader.byteValue());
            assertTrue(reader.exhausted());
        } finally {
            manager.close();
        }
    }

    @Test
    void returnsWrongDimensionAndOutOfBoundsResultsWithoutLoadingChunks() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("subchunk-bounds-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            manager.onIdentity("viewer", new NetherNetClientIdentity(
                    "viewer-token", new byte[] {1, 2, 3}, "viewer-xuid",
                    "Viewer", "viewer-playfab", null, null));
            Object session = session(manager, "viewer");
            set(session, "loginAuthenticated", true);
            set(session, "gameStarted", true);
            set(session, "positionInitialized", true);
            set(session, "compressionNegotiated", true);
            sentChunks(session).add(chunkKey(0, 0));

            NetherNetInboundPipeline.InboundMessage message =
                    new NetherNetInboundPipeline.InboundMessage("viewer", "reliable", true, new byte[0]);
            Method handle = BedrockSessionManager.class.getDeclaredMethod(
                    "handleSubChunkRequest", NetherNetInboundPipeline.InboundMessage.class,
                    session.getClass(), BedrockPacket.class);
            handle.setAccessible(true);
            handle.invoke(manager, message, session,
                    request(1, 0, 0, 0, new BedrockSubChunkCodec.Offset(0, 0, 0)));
            handle.invoke(manager, message, session,
                    request(0, 0, 20, 0, new BedrockSubChunkCodec.Offset(0, 0, 0)));

            List<BedrockPacket> packets = packetsFor(outbound, "viewer");
            assertEquals(2, packets.size());
            assertEquals(BedrockSubChunkCodec.RESULT_WRONG_DIMENSION,
                    readFirstResult(packets.get(0)));
            assertEquals(BedrockSubChunkCodec.RESULT_INDEX_OUT_OF_BOUNDS,
                    readFirstResult(packets.get(1)));
        } finally {
            manager.close();
        }
    }

    private static BedrockPacket request(
            int dimension, int centerX, int centerY, int centerZ, BedrockSubChunkCodec.Offset... offsets
    ) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeVarUInt32(out, (dimension << 1) ^ (dimension >> 31));
        writeVarUInt32(out, offsets.length);
        for (BedrockSubChunkCodec.Offset offset : offsets) {
            out.write(offset.x());
            out.write(offset.y());
            out.write(offset.z());
        }
        writeInt32(out, centerX);
        writeInt32(out, centerY);
        writeInt32(out, centerZ);
        return new BedrockPacket(BedrockSubChunkCodec.SUB_CHUNK_REQUEST_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static int readFirstResult(BedrockPacket packet) {
        ResponseReader reader = new ResponseReader(packet.payload());
        reader.bool();
        reader.varInt32();
        reader.int32();
        reader.int32();
        reader.int32();
        reader.varUInt32();
        reader.offset();
        return reader.byteValue();
    }

    private static void writeVarUInt32(java.io.ByteArrayOutputStream out, int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static void writeInt32(java.io.ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    @SuppressWarnings("unchecked")
    private static Object session(BedrockSessionManager manager, String id) throws Exception {
        Field field = BedrockSessionManager.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return ((Map<String, Object>) field.get(manager)).get(id);
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> sentChunks(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("sentChunkKeys");
        field.setAccessible(true);
        return (Set<Long>) field.get(session);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }

    private static List<BedrockPacket> packetsFor(List<Outbound> frames, String recipient) {
        List<BedrockPacket> packets = new ArrayList<>();
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        for (Outbound outbound : frames) {
            if (!recipient.equals(outbound.recipient())) continue;
            reassembler.accept(outbound.frame(), true).ifPresent(
                    message -> packets.addAll(BedrockBatchCodec.decode(message, true)));
        }
        return packets;
    }

    private record Outbound(String recipient, byte[] frame) {
    }

    private static final class ResponseReader {
        private final byte[] data;
        private int offset;

        private ResponseReader(byte[] data) { this.data = data; }

        boolean bool() { return byteValue() != 0; }

        int byteValue() { return Byte.toUnsignedInt(data[offset++]); }

        int int32() {
            int value = byteValue() | byteValue() << 8 | byteValue() << 16 | byteValue() << 24;
            return value;
        }

        int varUInt32() {
            int value = 0;
            int shift = 0;
            while (true) {
                int next = byteValue();
                value |= (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
                shift += 7;
                if (shift > 28) throw new AssertionError("VarUInt32 too long");
            }
        }

        int varInt32() {
            int value = varUInt32();
            return (value >>> 1) ^ -(value & 1);
        }

        BedrockSubChunkCodec.Offset offset() {
            return new BedrockSubChunkCodec.Offset((byte) byteValue(), (byte) byteValue(), (byte) byteValue());
        }

        byte[] byteArray() {
            int length = varUInt32();
            byte[] value = java.util.Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return value;
        }

        boolean exhausted() { return offset == data.length; }
    }
}
