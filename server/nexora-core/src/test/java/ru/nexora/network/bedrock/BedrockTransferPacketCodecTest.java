package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockTransferPacketCodecTest {
    @Test
    void encodesProtocol2225TransferPacketWithOptionalGatheringsAbsent() {
        BedrockPacket packet = BedrockTransferPacketCodec.create("target.example.net", 19132, true);
        assertEquals(0x55, packet.packetId());

        byte[] payload = packet.payload();
        int[] offset = {0};
        int length = readVarUInt(payload, offset);
        assertEquals("target.example.net",
                new String(payload, offset[0], length, StandardCharsets.UTF_8));
        offset[0] += length;
        assertEquals(19132, Byte.toUnsignedInt(payload[offset[0]++])
                | (Byte.toUnsignedInt(payload[offset[0]++]) << 8));
        assertEquals(1, Byte.toUnsignedInt(payload[offset[0]++]));
        assertEquals(0, Byte.toUnsignedInt(payload[offset[0]++]));
        assertEquals(payload.length, offset[0]);
    }

    @Test
    void validatesTransferAddressAndPort() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockTransferPacketCodec.create("bad\nhost", 19132, true));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockTransferPacketCodec.create("target.example.net", 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockTransferPacketCodec.create("target.example.net", 65_536, true));
    }

    private static int readVarUInt(byte[] bytes, int[] offset) {
        int result = 0;
        int shift = 0;
        while (true) {
            int next = Byte.toUnsignedInt(bytes[offset[0]++]);
            result |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return result;
            shift += 7;
            if (shift > 28) throw new IllegalArgumentException("VarUInt overflow");
        }
    }
}
