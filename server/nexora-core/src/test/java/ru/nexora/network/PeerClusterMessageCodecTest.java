package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PeerClusterMessageCodecTest {
    @Test
    void pairwiseKeyAuthenticatesConfiguredSourceIdentity() throws IOException {
        byte[] sharedPairKey = new byte[32];
        java.util.Arrays.fill(sharedPairKey, (byte) 0x44);
        AuthenticatedClusterMessageCodec aToB = new AuthenticatedClusterMessageCodec(sharedPairKey, 5_000);
        AuthenticatedClusterMessageCodec bFromA = new AuthenticatedClusterMessageCodec(sharedPairKey, 5_000);
        PeerClusterMessageCodec nodeA = new PeerClusterMessageCodec("node-a", Map.of("node-b", aToB));
        PeerClusterMessageCodec nodeB = new PeerClusterMessageCodec("node-b", Map.of("node-a", bFromA));
        ClusterMessage message = new ClusterMessage(UUID.randomUUID(), "node-a", "node-b",
                "HEARTBEAT", 1_000, new byte[] {1});

        ClusterMessage decoded = nodeB.decode(nodeA.encode(message), "node-b", 1_000);
        assertEquals("node-a", decoded.sourceNode());
        assertEquals(message.messageId(), decoded.messageId());
    }

    @Test
    void rejectsSourceSpoofedWithAnotherPeersPairwiseKey() throws IOException {
        byte[] pairKey = new byte[32];
        java.util.Arrays.fill(pairKey, (byte) 0x55);
        AuthenticatedClusterMessageCodec codec = new AuthenticatedClusterMessageCodec(pairKey, 5_000);
        PeerClusterMessageCodec nodeB = new PeerClusterMessageCodec("node-b", Map.of("node-a", codec));
        ClusterMessage forged = new ClusterMessage(UUID.randomUUID(), "node-c", "node-b",
                "HEARTBEAT", 1_000, new byte[0]);

        assertThrows(IOException.class,
                () -> nodeB.decode(codec.encode(forged), "node-b", 1_000));
    }

    @Test
    void refusesToSignAsAnotherSourceOrForUnknownTarget() throws IOException {
        PeerClusterMessageCodec nodeA = new PeerClusterMessageCodec("node-a",
                Map.of("node-b", new AuthenticatedClusterMessageCodec(new byte[32], 5_000)));
        ClusterMessage forgedSource = new ClusterMessage(UUID.randomUUID(), "node-c", "node-b",
                "HEARTBEAT", 1_000, new byte[0]);
        ClusterMessage unknownTarget = new ClusterMessage(UUID.randomUUID(), "node-a", "node-c",
                "HEARTBEAT", 1_000, new byte[0]);

        assertThrows(IOException.class, () -> nodeA.encode(forgedSource));
        assertThrows(IOException.class, () -> nodeA.encode(unknownTarget));
    }
}
