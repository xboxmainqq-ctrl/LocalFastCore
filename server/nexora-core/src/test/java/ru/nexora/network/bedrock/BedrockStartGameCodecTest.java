package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockStartGameCodecTest {
    @Test
    void encodesProtocol2225StartGameHeaderAndSpawnPosition() {
        BedrockPacket packet = BedrockStartGameCodec.create(
                7, "NEXORA World", "1.26.62", "1.26.62", 0.5f, 65.0f, 0.5f);

        assertEquals(0x0b, packet.packetId());
        byte[] payload = packet.payload();
        assertTrue(payload.length > 180);

        // EntityUniqueID=7 (signed VarInt -> 14), EntityRuntimeID=7 (VarUInt), GameMode=survival (0).
        assertEquals(14, Byte.toUnsignedInt(payload[0]));
        assertEquals(7, Byte.toUnsignedInt(payload[1]));
        assertEquals(0, Byte.toUnsignedInt(payload[2]));

        ByteBuffer position = ByteBuffer.wrap(payload, 3, 12).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0.5f, position.getFloat());
        assertEquals(65.0f, position.getFloat());
        assertEquals(0.5f, position.getFloat());

        byte[] worldName = "NEXORA World".getBytes(StandardCharsets.UTF_8);
        assertTrue(contains(payload, worldName));
        assertTrue(contains(payload, "1.26.62".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsInvalidEntityIdsAndBlankWorldMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockStartGameCodec.create(0, "NEXORA", "1.26.62", "1.26.62", 0, 65, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockStartGameCodec.create(1, " ", "1.26.62", "1.26.62", 0, 65, 0));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
