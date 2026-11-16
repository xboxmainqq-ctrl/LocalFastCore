package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayerActionCodecTest {
    @Test
    void decodesRespawnActionAndAllProtocol2225PositionFields() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarUInt(out, 1234);
        writeVarInt(out, BedrockPlayerActionCodec.ACTION_RESPAWN);
        for (int value : new int[] {1, 64, 2, 1, 65, 2, 0}) writeVarInt(out, value);
        var action = BedrockPlayerActionCodec.decode(
                new BedrockPacket(BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID, 0, 0, out.toByteArray()));
        assertEquals(1234, action.runtimeEntityId());
        assertEquals(BedrockPlayerActionCodec.ACTION_RESPAWN, action.action());
        assertEquals(1, action.x());
        assertEquals(64, action.y());
        assertEquals(2, action.z());
        assertEquals(1, action.resultX());
        assertEquals(65, action.resultY());
        assertEquals(2, action.resultZ());
        assertEquals(0, action.face());
    }

    @Test
    void rejectsTruncatedAndMismatchedPackets() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockPlayerActionCodec.decode(new BedrockPacket(0x24, 0, 0, new byte[] {1})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockPlayerActionCodec.decode(new BedrockPacket(0x25, 0, 0, new byte[0])));
    }

    private static void writeVarUInt(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }
    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        writeVarUInt(out, Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
    }
}
