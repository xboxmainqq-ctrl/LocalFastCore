package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.network.DurableHandoffCoordinator;
import ru.nexora.network.InMemoryPlayerTransferLedger;
import ru.nexora.network.PlayerHandoff;
import ru.nexora.network.PlayerTransferClusterEndpoint;
import ru.nexora.network.AuthenticatedClusterMessageCodec;
import ru.nexora.network.ClusterReplayGuard;
import ru.nexora.network.TcpClusterTransport;
import ru.nexora.network.PlayerTransferCoordinator;
import ru.nexora.network.PlayerTransferRecord;
import ru.nexora.network.PlayerTransferSourceCoordinator;
import ru.nexora.network.CheckpointedPlayerTransferDestination;
import ru.nexora.network.bedrock.BedrockPlayerTransferSource;
import ru.nexora.network.bedrock.BedrockPendingTransferActivator;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.persistence.FilePlayerStateStore;
import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;
import ru.nexora.world.World;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferSourceCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void freezesAcceptsCommitsAndRedirectsThroughTheBedrockSessionBridge() throws Exception {
        String xuid = "source-coordinator-xuid";
        PlayerStateCheckpointService checkpoints = new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory.resolve("shared-checkpoints")),
                new PlayerStateBundleCodec());
        List<byte[]> outboundFrames = new java.util.concurrent.CopyOnWriteArrayList<>();
        BedrockSessionManager sourceSessions = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("source-world", 0L, new FlatWorldGenerator()),
                null, null, null, checkpoints,
                (networkId, label, frame) -> outboundFrames.add(frame), event -> {});
        BedrockSessionManager targetSessions = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("target-world", 0L, new FlatWorldGenerator()),
                null, null, null, checkpoints,
                (networkId, label, frame) -> { }, event -> {});
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        AtomicLong now = new AtomicLong(1_000L);
        try {
            sourceSessions.onIdentity("source-connection", new NetherNetClientIdentity(
                    "source-token", new byte[] {7}, xuid,
                    "SourcePlayer", "source-playfab", "20CA2", null));
            setSessionField(sourceSessions, "source-connection", "loginAuthenticated", true);
            setSessionField(sourceSessions, "source-connection", "clientInitialized", true);
            setSessionField(sourceSessions, "source-connection", "compressionNegotiated", true);
            setSessionField(sourceSessions, "source-connection", "positionInitialized", true);
            setSessionField(sourceSessions, "source-connection", "positionX", 12.0f);
            setSessionField(sourceSessions, "source-connection", "positionY", 70.0f);
            setSessionField(sourceSessions, "source-connection", "positionZ", -4.0f);

            CheckpointedPlayerTransferDestination destination = new CheckpointedPlayerTransferDestination(
                    ledger, checkpoints, "target-node",
                    new BedrockPendingTransferActivator(targetSessions), System::currentTimeMillis);
            PlayerTransferClusterEndpoint endpoint = new PlayerTransferClusterEndpoint(
                    "target-node", ledger, destination, 5_000L, System::currentTimeMillis);
            int sourcePort = availablePort();
            int targetPort = availablePort();
            InetSocketAddress sourceAddress = new InetSocketAddress("127.0.0.1", sourcePort);
            InetSocketAddress targetAddress = new InetSocketAddress("127.0.0.1", targetPort);
            byte[] clusterSecret = new byte[32];
            java.util.Arrays.fill(clusterSecret, (byte) 0x5a);
            TcpClusterTransport targetTransport = new TcpClusterTransport(
                    "target-node", targetAddress, Map.of("source-node", sourceAddress), java.util.Set.of("source-node"),
                    new AuthenticatedClusterMessageCodec(clusterSecret, 60_000L),
                    new ClusterReplayGuard(128, 60_000L),
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 2, 32);
            TcpClusterTransport sourceTransport = new TcpClusterTransport(
                    "source-node", sourceAddress, Map.of("target-node", targetAddress), java.util.Set.of("target-node"),
                    new AuthenticatedClusterMessageCodec(clusterSecret, 60_000L),
                    new ClusterReplayGuard(128, 60_000L),
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 2, 32);
            targetTransport.start(message -> {
                try {
                    endpoint.handle(message);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            sourceTransport.start(message -> { });
            PlayerTransferSourceCoordinator source = new PlayerTransferSourceCoordinator(
                    "source-node", ledger,
                    new BedrockPlayerTransferSource(sourceSessions, "source-node", ignored -> xuid),
                    sourceSessions, sourceTransport, Duration.ofSeconds(3), System::currentTimeMillis);

            PlayerHandoff handoff = new PlayerHandoff(
                    UUID.randomUUID(), 7L, "source-node", "target-node", new byte[0]);
            try {
                PlayerTransferRecord committed = source.transfer(
                        handoff, xuid, "target.example.net", 19132);

                assertEquals(PlayerTransferRecord.State.COMMITTED, committed.state());
                assertEquals(xuid, committed.playerXuid());
                assertTrue((boolean) getSessionField(sourceSessions, "source-connection", "transferFrozen"));
                assertTrue((boolean) getSessionField(sourceSessions, "source-connection", "transferRedirected"));
                assertTrue((boolean) getSessionField(sourceSessions, "source-connection", "rejected"));
                assertFalse(outboundFrames.isEmpty(), "Source must send the Bedrock TransferPacket after commit");
                assertTrue(targetSessions.deactivateTransferRestore(
                        xuid, handoff.transferId(), committed.fencingToken()),
                        "Destination must have reserved the exact checkpoint for the next login");
            } finally {
                sourceTransport.close();
                targetTransport.close();
            }
        } finally {
            sourceSessions.close();
            targetSessions.close();
        }
    }

    private static void setSessionField(BedrockSessionManager manager, String networkId,
                                        String name, Object value) throws Exception {
        Field sessionsField = BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> sessions = (Map<String, Object>) sessionsField.get(manager);
        Object state = sessions.get(networkId);
        assertNotNull(state);
        Field field = state.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(state, value);
    }

    private static Object getSessionField(BedrockSessionManager manager, String networkId,
                                          String name) throws Exception {
        Field sessionsField = BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> sessions = (Map<String, Object>) sessionsField.get(manager);
        Object state = sessions.get(networkId);
        assertNotNull(state);
        Field field = state.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(state);
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
