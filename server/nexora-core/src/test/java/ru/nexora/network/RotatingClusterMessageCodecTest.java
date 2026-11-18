package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RotatingClusterMessageCodecTest {
    @Test
    void acceptsPreviousKeyDuringRotationButSignsWithActiveKey() throws IOException {
        byte[] oldSecret = new byte[32];
        byte[] newSecret = new byte[32];
        java.util.Arrays.fill(oldSecret, (byte) 1);
        java.util.Arrays.fill(newSecret, (byte) 2);
        AuthenticatedClusterMessageCodec oldCodec = new AuthenticatedClusterMessageCodec(oldSecret, 5_000);
        AuthenticatedClusterMessageCodec newCodec = new AuthenticatedClusterMessageCodec(newSecret, 5_000);
        RotatingClusterMessageCodec rotating = new RotatingClusterMessageCodec(
                newCodec, List.of(newCodec, oldCodec));
        ClusterMessage message = new ClusterMessage(UUID.randomUUID(), "node-a", "node-b",
                "HEARTBEAT", 10_000, new byte[] {4});

        byte[] oldEnvelope = oldCodec.encode(message);
        byte[] newEnvelope = rotating.encode(message);
        assertEquals(message.messageId(), rotating.decode(oldEnvelope, "node-b", 10_000).messageId());
        assertEquals(message.messageId(), newCodec.decode(newEnvelope, "node-b", 10_000).messageId());
    }

    @Test
    void rejectsMessagesSignedByRetiredKey() throws IOException {
        byte[] oldSecret = new byte[32];
        byte[] activeSecret = new byte[32];
        byte[] retiredSecret = new byte[32];
        java.util.Arrays.fill(oldSecret, (byte) 1);
        java.util.Arrays.fill(activeSecret, (byte) 2);
        java.util.Arrays.fill(retiredSecret, (byte) 3);
        AuthenticatedClusterMessageCodec oldCodec = new AuthenticatedClusterMessageCodec(oldSecret, 5_000);
        AuthenticatedClusterMessageCodec active = new AuthenticatedClusterMessageCodec(activeSecret, 5_000);
        AuthenticatedClusterMessageCodec retired = new AuthenticatedClusterMessageCodec(retiredSecret, 5_000);
        RotatingClusterMessageCodec rotating = new RotatingClusterMessageCodec(active, List.of(active, oldCodec));
        ClusterMessage message = new ClusterMessage(UUID.randomUUID(), "node-a", "node-b",
                "HEARTBEAT", 10_000, new byte[0]);

        assertThrows(IOException.class,
                () -> rotating.decode(retired.encode(message), "node-b", 10_000));
    }

    @Test
    void limitsAcceptedKeyRingSize() {
        AuthenticatedClusterMessageCodec codec = new AuthenticatedClusterMessageCodec(new byte[32], 100);
        assertThrows(IllegalArgumentException.class,
                () -> new RotatingClusterMessageCodec(codec, List.of()));
    }
}
