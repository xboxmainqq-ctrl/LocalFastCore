package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.network.AuthenticatedClusterMessageCodec;
import ru.nexora.network.CheckpointedPlayerTransferDestination;
import ru.nexora.network.ClusterMessage;
import ru.nexora.network.ClusterReplayGuard;
import ru.nexora.network.ClusterTransport;
import ru.nexora.network.DurableHandoffCoordinator;
import ru.nexora.network.InMemoryPlayerTransferLedger;
import ru.nexora.network.PlayerHandoff;
import ru.nexora.network.PlayerTransferClusterEndpoint;
import ru.nexora.network.PlayerTransferCoordinator;
import ru.nexora.network.PlayerTransferLifecycle;
import ru.nexora.network.PlayerTransferRecord;
import ru.nexora.network.PlayerTransferSourceCoordinator;
import ru.nexora.network.PeerClusterMessageCodec;
import ru.nexora.network.TcpClusterTransport;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.persistence.FilePlayerStateStore;
import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
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
                    new PeerClusterMessageCodec("target-node", Map.of("source-node",
                            new AuthenticatedClusterMessageCodec(clusterSecret, 60_000L))),
                    new ClusterReplayGuard(128, 60_000L),
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 2, 32);
            TcpClusterTransport sourceTransport = new TcpClusterTransport(
                    "source-node", sourceAddress, Map.of("target-node", targetAddress), java.util.Set.of("target-node"),
                    new PeerClusterMessageCodec("source-node", Map.of("target-node",
                            new AuthenticatedClusterMessageCodec(clusterSecret, 60_000L))),
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

    @Test
    void failedAcknowledgementAbortsClaimNotifiesDestinationAndResumesSource() throws Exception {
        PlayerStateCheckpointService checkpoints = new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory.resolve("abort-checkpoints")),
                new PlayerStateBundleCodec());
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean sourceUnfrozen = new AtomicBoolean();
        AtomicBoolean destinationDeactivated = new AtomicBoolean();
        PlayerTransferLifecycle.Source sourceAdapter = new PlayerTransferLifecycle.Source() {
            @Override
            public long freezeAndCheckpoint(PlayerHandoff handoff) {
                return 0L;
            }

            @Override
            public void unfreeze(PlayerHandoff handoff) {
                sourceUnfrozen.set(true);
            }
        };
        CheckpointedPlayerTransferDestination destination = new CheckpointedPlayerTransferDestination(
                ledger, checkpoints, "target-node",
                new CheckpointedPlayerTransferDestination.Activator() {
                    @Override
                    public boolean restoreAndActivate(PlayerHandoff handoff,
                                                       PlayerStateBundle bundle,
                                                       ru.nexora.network.PlayerTransferFence fence) {
                        return true;
                    }

                    @Override
                    public void deactivate(PlayerHandoff handoff,
                                           ru.nexora.network.PlayerTransferFence fence) {
                        destinationDeactivated.set(true);
                    }
                }, now::get);
        PlayerTransferClusterEndpoint endpoint = new PlayerTransferClusterEndpoint(
                "target-node", ledger, destination, 5_000L, now::get);
        ClusterTransport transport = new ClusterTransport() {
            @Override
            public void start(java.util.function.Consumer<ClusterMessage> receiver) {
            }

            @Override
            public CompletionStage<Void> send(ClusterMessage message) {
                try {
                    if (PlayerTransferClusterEndpoint.ACCEPT_MESSAGE_TYPE.equals(message.type())) {
                        java.nio.ByteBuffer payload = java.nio.ByteBuffer.wrap(message.payload());
                        UUID id = new UUID(payload.getLong(), payload.getLong());
                        ledger.claim(id, "target-node", now.get(), 5_000L).orElseThrow();
                        return CompletableFuture.failedFuture(new java.io.IOException("simulated lost ACK"));
                    }
                    endpoint.handle(message);
                    return CompletableFuture.completedFuture(null);
                } catch (Exception failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }

            @Override
            public void close() {
            }
        };
        BedrockSessionManager sourceSessions = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("abort-source-world", 0L, new FlatWorldGenerator()),
                null, null, null, checkpoints, (networkId, label, frame) -> { }, event -> {});
        try {
            PlayerTransferSourceCoordinator source = new PlayerTransferSourceCoordinator(
                    "source-node", ledger, sourceAdapter, sourceSessions,
                    transport, Duration.ofSeconds(1), now::get);
            PlayerHandoff handoff = new PlayerHandoff(
                    UUID.randomUUID(), 9L, "source-node", "target-node", new byte[0]);

            assertThrows(java.io.IOException.class, () -> source.transfer(
                    handoff, "abort-xuid", "target.example.net", 19132));
            PlayerTransferRecord record = ledger.get(handoff.transferId()).orElseThrow();
            assertEquals(PlayerTransferRecord.State.ABORTED, record.state());
            assertTrue(sourceUnfrozen.get());
            assertTrue(destinationDeactivated.get(),
                    "Remote abort notification must clear the matching destination reservation");
        } finally {
            sourceSessions.close();
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
