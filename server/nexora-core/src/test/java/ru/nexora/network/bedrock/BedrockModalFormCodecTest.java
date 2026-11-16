package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockModalFormCodecTest {
    @Test
    void createsProtocol2225ModalFormRequest() throws java.io.IOException {
        BedrockPacket packet = BedrockModalFormCodec.createRequest(300, "{\"type\":\"form\",\"title\":\"NEXORA\"}");
        assertEquals(0x64, packet.packetId());
        Reader reader = new Reader(packet.payload());
        assertEquals(300, reader.varUInt32());
        assertEquals("{\"type\":\"form\",\"title\":\"NEXORA\"}", reader.string());
        reader.requireExhausted();
    }

    @Test
    void decodesSimpleFormResponse() {
        byte[] payload = response(17, "[2]", null);
        var decoded = BedrockModalFormCodec.decodeResponse(
                new BedrockPacket(0x65, 0, 0, payload));
        assertEquals(17, decoded.formId());
        assertEquals("[2]", decoded.responseJson());
        assertNull(decoded.cancelReason());
        assertFalse(decoded.cancelled());
    }

    @Test
    void decodesUserCancellationAndBusyReason() {
        var closed = BedrockModalFormCodec.decodeResponse(
                new BedrockPacket(0x65, 0, 0, response(3, null, 0)));
        assertTrue(closed.cancelled());
        assertEquals(0, closed.cancelReason());

        var busy = BedrockModalFormCodec.decodeResponse(
                new BedrockPacket(0x65, 0, 0, response(4, null, 1)));
        assertTrue(busy.cancelled());
        assertEquals(1, busy.cancelReason());
    }

    @Test
    void rejectsInvalidPresenceFlagsTrailingBytesAndOversizedJson() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockModalFormCodec.decodeResponse(new BedrockPacket(0x65, 0, 0,
                        new byte[]{1, 2, 0})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockModalFormCodec.decodeResponse(new BedrockPacket(0x65, 0, 0,
                        new byte[]{1, 0, 0, 99})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockModalFormCodec.createRequest(-1, "{}"));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockModalFormCodec.createRequest(1, "x".repeat(70_000)));
    }

    private static byte[] response(int id, String json, Integer cancelReason) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarUInt(out, id);
        out.write(json == null ? 0 : 1);
        if (json != null) writeString(out, json);
        out.write(cancelReason == null ? 0 : 1);
        if (cancelReason != null) out.write(cancelReason);
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarUInt(ByteArrayOutputStream out, int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static final class Reader {
        private final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(new byte[0]);
        private final java.io.ByteArrayInputStream input;

        Reader(byte[] bytes) {
            input = new java.io.ByteArrayInputStream(bytes);
        }

        int varUInt32() {
            int value = 0, shift = 0, current;
            do {
                current = input.read();
                if (current < 0 || shift >= 35) throw new AssertionError("bad varuint");
                value |= (current & 0x7f) << shift;
                shift += 7;
            } while ((current & 0x80) != 0);
            return value;
        }

        String string() {
            int length = varUInt32();
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new AssertionError("truncated string");
            return new String(bytes, StandardCharsets.UTF_8);
        }

        void requireExhausted() {
            assertEquals(0, input.available());
        }
    }
}
