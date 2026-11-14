package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NexoraPrivateProtocolCodecTest {
    @Test
    void clientHelloMatchesTheSharedAndroidGoldenVector() {
        byte[] expected = new byte[] {
                'N', 'X', 'R', 'A', 1, 0, 1, 11,
                'a', 'n', 'd', 'r', 'o', 'i', 'd', '-', 'd', 'e', 'v', 10,
                (byte) 0xd0, (byte) 0x98, (byte) 0xd0, (byte) 0xb3,
                (byte) 0xd0, (byte) 0xb8, (byte) 0xd0, (byte) 0xba
        };
        assertArrayEquals(expected, NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));
        NexoraPrivateProtocolCodec.ClientHello hello = NexoraPrivateProtocolCodec.decodeClientHello(ByteBuffer.wrap(expected));
        assertEquals("android-dev", hello.clientBuild());
        assertEquals("Игрок", hello.playerName());
    }

    @Test
    void clientHelloRoundTripsWithVersionAndBuild() {
        byte[] encoded = NexoraPrivateProtocolCodec.encodeClientHello("android-dev-7");
        NexoraPrivateProtocolCodec.ClientHello decoded =
                NexoraPrivateProtocolCodec.decodeClientHello(ByteBuffer.wrap(encoded));

        assertEquals(1, decoded.majorVersion());
        assertEquals(0, decoded.minorVersion());
        assertEquals("android-dev-7", decoded.clientBuild());
        assertEquals("Игрок", decoded.playerName());
    }

    @Test
    void serverHelloRoundTripsServerIdentityAndMessageLimit() {
        UUID serverId = UUID.fromString("f8d2c8e0-a2c4-4a87-a69d-02e7d8dff921");
        byte[] encoded = NexoraPrivateProtocolCodec.encodeServerHello(serverId, 1024 * 1024);
        NexoraPrivateProtocolCodec.ServerResponse decoded =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(encoded));

        assertTrue(decoded.accepted());
        assertEquals(serverId, decoded.serverId());
        assertEquals(1024 * 1024, decoded.maxMessageBytes());
    }

    @Test
    void rejectionRoundTripsCodeAndReason() {
        byte[] encoded = NexoraPrivateProtocolCodec.encodeReject(3, "Version mismatch");
        NexoraPrivateProtocolCodec.ServerResponse decoded =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(encoded));

        assertFalse(decoded.accepted());
        assertEquals(3, decoded.rejectionCode());
        assertEquals("Version mismatch", decoded.rejectionReason());
    }

    @Test
    void worldInfoRoundTripsSpawnHeightAndRuntimeCounts() {
        NexoraWorldInfo expected = new NexoraWorldInfo(
                "world", -64, 320, 0, 65, 0, 20, 1);
        byte[] encoded = NexoraPrivateProtocolCodec.encodeWorldInfo(expected);
        NexoraWorldInfo decoded =
                NexoraPrivateProtocolCodec.decodeWorldInfo(ByteBuffer.wrap(encoded));

        assertEquals(expected, decoded);
    }

    @Test
    void worldInfoMatchesTheAndroidWireVector() {
        NexoraWorldInfo world = new NexoraWorldInfo(
                "world", -64, 320, 0, 65, 0, 20, 1);
        ByteBuffer expected = ByteBuffer.allocate(7 + 1 + 5 + 28);
        expected.put(new byte[] {'N', 'X', 'R', 'A', 1, 0, 4});
        expected.put((byte) 5).put("world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        expected.putInt(-64).putInt(320);
        expected.putInt(0).putInt(65).putInt(0);
        expected.putInt(20).putInt(1);

        assertArrayEquals(expected.array(), NexoraPrivateProtocolCodec.encodeWorldInfo(world));
    }

    @Test
    void rejectsTruncatedAndOversizedMessages() {
        assertThrows(IllegalArgumentException.class,
                () -> NexoraPrivateProtocolCodec.decodeClientHello(ByteBuffer.wrap(new byte[] {1, 2})));
        assertThrows(IllegalArgumentException.class,
                () -> NexoraPrivateProtocolCodec.decodeServerResponse(
                        ByteBuffer.wrap(new byte[NexoraPrivateProtocolCodec.MAX_MESSAGE_BYTES + 1])));
    }

    @Test
    void rejectsInvalidBuildLength() {
        byte[] encoded = NexoraPrivateProtocolCodec.encodeClientHello("test");
        encoded[7] = 0;
        assertThrows(IllegalArgumentException.class,
                () -> NexoraPrivateProtocolCodec.decodeClientHello(ByteBuffer.wrap(encoded)));
    }
}
