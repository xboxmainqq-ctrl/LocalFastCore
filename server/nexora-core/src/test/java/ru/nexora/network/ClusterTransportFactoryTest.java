package ru.nexora.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTransportFactoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesAuthenticatedPeerAllowlistAndHidesSharedSecret() throws Exception {
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        ClusterTransportFactory.ConfiguredTransport configured =
                ClusterTransportFactory.open("source-node", temporaryDirectory, Map.of(
                        "NEXORA_CLUSTER_BIND", "127.0.0.1:19141",
                        "NEXORA_CLUSTER_PEERS", "target-node=127.0.0.1:19142;backup-node=127.0.0.1:19143",
                        "NEXORA_CLUSTER_BEDROCK_ROUTES", "target-node=127.0.0.1:19132",
                        "NEXORA_CLUSTER_PEER_KEYS", "target-node=" + secret + ";backup-node=" + secret)).orElseThrow();
        try {
            assertEquals(2, configured.peerCount());
            assertEquals(19132, configured.bedrockRoutes().get("target-node").getPort());
            assertTrue(configured.description().contains("HMAC-SHA-256"));
            assertFalse(configured.description().contains(secret));
        } finally {
            configured.transport().close();
        }
    }

    @Test
    void disablesTransportWhenNoClusterSettingsArePresent() {
        assertTrue(ClusterTransportFactory.open(
                "standalone", temporaryDirectory, Map.of()).isEmpty());
    }

    @Test
    void rejectsPartialInvalidAndSelfReferencingPeerConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> ClusterTransportFactory.open(
                "source-node", temporaryDirectory,
                Map.of("NEXORA_CLUSTER_BIND", "127.0.0.1:19141")));
        assertThrows(IllegalArgumentException.class, () -> ClusterTransportFactory.open(
                "source-node", temporaryDirectory,
                Map.of("NEXORA_CLUSTER_BEDROCK_ROUTES", "target-node=127.0.0.1:19132")));
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        assertThrows(IllegalArgumentException.class, () -> ClusterTransportFactory.open(
                "source-node", temporaryDirectory, Map.of(
                        "NEXORA_CLUSTER_BIND", "127.0.0.1:19141",
                        "NEXORA_CLUSTER_PEERS", "source-node=127.0.0.1:19142",
                        "NEXORA_CLUSTER_PEER_KEYS", "source-node=" + secret)));
        assertThrows(IllegalArgumentException.class, () -> ClusterTransportFactory.open(
                "source-node", temporaryDirectory, Map.of(
                        "NEXORA_CLUSTER_BIND", "127.0.0.1:19141",
                        "NEXORA_CLUSTER_PEERS", "target-node=127.0.0.1:19142",
                        "NEXORA_CLUSTER_PEER_KEYS", "target-node=not-base64")));
        assertThrows(IllegalArgumentException.class, () -> ClusterTransportFactory.open(
                "source-node", temporaryDirectory, Map.of(
                        "NEXORA_CLUSTER_BIND", "127.0.0.1:19141",
                        "NEXORA_CLUSTER_PEERS", "target-node=127.0.0.1:19142",
                        "NEXORA_CLUSTER_BEDROCK_ROUTES", "unknown-node=127.0.0.1:19132",
                        "NEXORA_CLUSTER_PEER_KEYS", "target-node=" + secret)));
    }
}
