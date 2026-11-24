package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockCommandRequestCodecTest {
    @Test
    void decodesPlayerCommandRequest() {
        BedrockPacket packet = new BedrockPacket(0x4d, 0, 0,
                command("/equipment", "Player", "request-123", 42L, false, "Latest"));
        var decoded = BedrockCommandRequestCodec.decode(packet);
        assertEquals("/equipment", decoded.command());
        assertEquals("Player", decoded.originType());
        assertEquals("request-123", decoded.requestId());
        assertEquals(42L, decoded.playerId());
        assertFalse(decoded.internal());
        assertEquals("Latest", decoded.version());
    }

    @Test
    void acceptsLowercasePlayerOriginUsedByBedrockCompatibleClients() {
        BedrockPacket packet = new BedrockPacket(0x4d, 0, 0,
                command("/equipment", "player", "request-lower", 42L, false, "Latest"));
        var decoded = BedrockCommandRequestCodec.decode(packet);
        assertEquals("Player", decoded.originType());
    }

    @Test
    void rejectsWrongPacketOriginMalformedBooleanAndTrailingData() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCommandRequestCodec.decode(new BedrockPacket(0x09, 0, 0, new byte[0])));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCommandRequestCodec.decode(new BedrockPacket(0x4d, 0, 0,
                        command("/equipment", "Unknown", "r", 1, false, "Latest"))));
        byte[] invalidBoolean = command("/equipment", "Player", "r", 1, false, "Latest");
        invalidBoolean[invalidBoolean.length - 8] = 2;
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCommandRequestCodec.decode(new BedrockPacket(0x4d, 0, 0, invalidBoolean)));
        byte[] trailing = java.util.Arrays.copyOf(
                command("/equipment", "Player", "r", 1, false, "Latest"), 1);
        trailing[trailing.length - 1] = 7;
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCommandRequestCodec.decode(new BedrockPacket(0x4d, 0, 0, trailing)));
    }

    private static byte[] command(String command, String origin, String requestId,
                                  long playerId, boolean internal, String version) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, command);
        writeString(out, origin);
        out.writeBytes(new byte[16]); // UUID
        writeString(out, requestId);
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(playerId).array());
        out.write(internal ? 1 : 0);
        writeString(out, version);
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
}
