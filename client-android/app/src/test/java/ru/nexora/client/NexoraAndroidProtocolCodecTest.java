package ru.nexora.client;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.Assert.*;

public class NexoraAndroidProtocolCodecTest {
    @Test
    public void clientHelloMatchesTheServerGoldenVector() {
        byte[] expected = new byte[] {
                'N', 'X', 'R', 'A', 1, 0, 1, 11,
                'a', 'n', 'd', 'r', 'o', 'i', 'd', '-', 'd', 'e', 'v'
        };
        assertArrayEquals(expected, NexoraAndroidProtocolCodec.encodeClientHello("android-dev"));
    }

    @Test
    public void encodesClientHelloWithExpectedMagicAndVersion() {
        byte[] encoded = NexoraAndroidProtocolCodec.encodeClientHello("android-dev");
        assertEquals(78, Byte.toUnsignedInt(encoded[0]));
        assertEquals(88, Byte.toUnsignedInt(encoded[1]));
        assertEquals(82, Byte.toUnsignedInt(encoded[2]));
        assertEquals(65, Byte.toUnsignedInt(encoded[3]));
        assertEquals(1, Byte.toUnsignedInt(encoded[4]));
        assertEquals(0, Byte.toUnsignedInt(encoded[5]));
        assertEquals(1, Byte.toUnsignedInt(encoded[6]));
    }

    @Test
    public void decodesServerHello() {
        UUID id = UUID.fromString("f8d2c8e0-a2c4-4a87-a69d-02e7d8dff921");
        ByteBuffer bytes = ByteBuffer.allocate(27);
        bytes.put(new byte[] {'N','X','R','A',1,0,2});
        bytes.putLong(id.getMostSignificantBits());
        bytes.putLong(id.getLeastSignificantBits());
        bytes.putInt(1024 * 1024);

        NexoraAndroidProtocolCodec.ServerResponse response =
                NexoraAndroidProtocolCodec.decodeServerResponse(bytes.array());
        assertTrue(response.accepted());
        assertEquals(id.toString(), response.serverId());
        assertEquals(1024 * 1024, response.maxMessageBytes());
    }

    @Test
    public void decodesServerRejection() {
        byte[] reason = "Unsupported version".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer bytes = ByteBuffer.allocate(9 + reason.length);
        bytes.put(new byte[] {'N','X','R','A',1,0,3,1,(byte) reason.length});
        bytes.put(reason);
        NexoraAndroidProtocolCodec.ServerResponse response =
                NexoraAndroidProtocolCodec.decodeServerResponse(bytes.array());
        assertFalse(response.accepted());
        assertEquals(1, response.rejectionCode());
        assertEquals("Unsupported version", response.rejectionReason());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongMagic() {
        NexoraAndroidProtocolCodec.decodeServerResponse(new byte[] {0,0,0,0,1,0,2});
    }
}
