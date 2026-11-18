package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedClusterMessageCodecTest {
    private final byte[] secret = new byte[32];

    @Test
    void authenticatedEnvelopeRoundTripsAndDefensivelyCopiesPayload() throws IOException {
        java.util.Arrays.fill(secret, (byte) 0x5a);
        AuthenticatedClusterMessageCodec codec = new AuthenticatedClusterMessageCodec(secret, 2_000);
        byte[] payload = new byte[] {1, 2, 3, 4};
        ClusterMessage message = new ClusterMessage(UUID.randomUUID(),
                "gateway-1", "world-2", "PLAYER_HANDOFF", 10_000, payload);
        payload[0] = 99;

        ClusterMessage decoded = codec.decode(codec.encode(message), "world-2", 10_500);
        assertEquals(message.messageId(), decoded.messageId());
        assertEquals("gateway-1", decoded.sourceNode());
        assertEquals("PLAYER_HANDOFF", decoded.type());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, decoded.payload());
    }

    @Test
    void rejectsTamperingWrongRecipientAndStaleTimestamp() throws IOException {
        java.util.Arrays.fill(secret, (byte) 0x2b);
        AuthenticatedClusterMessageCodec codec = new AuthenticatedClusterMessageCodec(secret, 500);
        ClusterMessage message = new ClusterMessage(UUID.randomUUID(),
                "node-a", "node-b", "HEARTBEAT", 10_000, new byte[] {8});
        byte[] encoded = codec.encode(message);
        byte[] tampered = encoded.clone();
        tampered[tampered.length / 2] ^= 1;

        assertThrows(IOException.class, () -> codec.decode(tampered, "node-b", 10_000));
        assertThrows(IOException.class, () -> codec.decode(encoded, "node-c", 10_000));
        assertThrows(IOException.class, () -> codec.decode(encoded, "node-b", 10_501));
    }

    @Test
    void requiresStrongSharedSecret() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedClusterMessageCodec(new byte[31], 100));
    }
}
