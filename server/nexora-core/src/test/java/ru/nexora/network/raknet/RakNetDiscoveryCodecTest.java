package ru.nexora.network.raknet;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RakNetDiscoveryCodecTest {
    private final BedrockServerStatus status = new BedrockServerStatus(
            "NEXORA Test", 2225, "1.26.62", 0, 20,
            "Development", "Survival", 1, 19132, 19132);

    @Test
    void encodesUnconnectedPongAndEchoesTimestamp() {
        long timestamp = 123456789L;
        long serverGuid = 0x1020304050607080L;
        byte[] request = ping(timestamp);

        byte[] pong = RakNetDiscoveryCodec.encodePong(request, serverGuid, status).orElseThrow();
        ByteBuffer buffer = ByteBuffer.wrap(pong).order(ByteOrder.BIG_ENDIAN);

        assertEquals(RakNetDiscoveryCodec.UNCONNECTED_PONG, Byte.toUnsignedInt(buffer.get()));
        assertEquals(timestamp, buffer.getLong());
        assertEquals(serverGuid, buffer.getLong());

        byte[] magic = new byte[16];
        buffer.get(magic);
        assertArrayEquals(RakNetDiscoveryCodec.magic(), magic);

        int stringLength = Short.toUnsignedInt(buffer.getShort());
        byte[] stringBytes = new byte[stringLength];
        buffer.get(stringBytes);
        assertEquals("MCPE;NEXORA Test;2225;1.26.62;0;20;"
                + Long.toUnsignedString(serverGuid)
                + ";Development;Survival;1;19132;19132",
                new String(stringBytes, StandardCharsets.UTF_8));
        assertFalse(buffer.hasRemaining());
    }

    @Test
    void rejectsWrongPacketId() {
        byte[] request = ping(10L);
        request[0] = 0x05;
        assertEquals(Optional.empty(), RakNetDiscoveryCodec.encodePong(request, 1L, status));
    }

    @Test
    void rejectsWrongMagic() {
        byte[] request = ping(10L);
        request[12] ^= 0x01;
        assertEquals(Optional.empty(), RakNetDiscoveryCodec.encodePong(request, 1L, status));
    }

    @Test
    void rejectsTruncatedPing() {
        assertEquals(Optional.empty(),
                RakNetDiscoveryCodec.encodePong(new byte[] {0x01, 0x00}, 1L, status));
    }

    private static byte[] ping(long timestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(RakNetDiscoveryCodec.MIN_PING_LENGTH)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) RakNetDiscoveryCodec.UNCONNECTED_PING);
        buffer.putLong(timestamp);
        buffer.put(RakNetDiscoveryCodec.magic());
        buffer.putLong(0x1122334455667788L);
        return buffer.array();
    }
}
