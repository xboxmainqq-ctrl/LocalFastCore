package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSubChunkCodecTest {
    @Test
    void matchesNukkitMotMaximumOf8192SubChunkOffsets() {
        BedrockPacket requestPacket = requestPacket(8192);
        BedrockSubChunkCodec.Request request = BedrockSubChunkCodec.decodeRequest(requestPacket);
        assertEquals(8192, request.offsets().size());

        List<BedrockSubChunkCodec.Response> responses = new ArrayList<>(8192);
        for (int i = 0; i < 8192; i++) {
            responses.add(new BedrockSubChunkCodec.Response(
                    new BedrockSubChunkCodec.Offset(0, 0, 0),
                    BedrockSubChunkCodec.RESULT_LEVEL_CHUNK_DOES_NOT_EXIST,
                    null));
        }
        BedrockPacket responsePacket = BedrockSubChunkCodec.createResponsePacket(request, responses);
        byte[] payload = responsePacket.payload();
        int offset = 1; // cacheEnabled
        offset += 1; // zig-zag VarInt dimension 0
        offset += 3 * Integer.BYTES; // center position
        assertEquals(8192, readVarUInt32(payload, offset));
    }

    @Test
    void rejectsMoreThanNukkitMotMaximumOf8192SubChunkOffsets() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkCodec.decodeRequest(requestPacket(8193)));
    }

    private static BedrockPacket requestPacket(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarUInt32(out, 0); // Overworld dimension
        writeVarUInt32(out, count);
        for (int i = 0; i < count; i++) {
            out.write(0); // x offset
            out.write(0); // y offset
            out.write(0); // z offset
        }
        writeInt32(out, 0);
        writeInt32(out, 0);
        writeInt32(out, 0);
        return new BedrockPacket(BedrockSubChunkCodec.SUB_CHUNK_REQUEST_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeVarUInt32(ByteArrayOutputStream out, int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static int readVarUInt32(byte[] data, int offset) {
        int value = 0;
        int shift = 0;
        while (true) {
            int next = Byte.toUnsignedInt(data[offset++]);
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
            if (shift > 28) throw new AssertionError("VarUInt32 too long");
        }
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }
}
