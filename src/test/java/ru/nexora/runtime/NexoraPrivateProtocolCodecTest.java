package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NexoraPrivateProtocolCodecTest {
    @Test
    void clientHelloRoundTripsWithVersionAndBuild() {
        byte[] encoded = NexoraPrivateProtocolCodec.encodeClientHello("android-dev-7");
        NexoraPrivateProtocolCodec.ClientHello decoded =
                NexoraPrivateProtocolCodec.decodeClientHello(ByteBuffer.wrap(encoded));

        assertEquals(1, decoded.majorVersion());
        assertEquals(0, decoded.minorVersion());
        assertEquals("android-dev-7", decoded.clientBuild());
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
