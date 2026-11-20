package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.DurableHandoffCoordinator;
import ru.nexora.network.InMemoryPlayerTransferLedger;
import ru.nexora.network.InMemoryPlayerOnlineLeaseRegistry;
import ru.nexora.network.PlayerHandoff;
import ru.nexora.network.PlayerTransferCoordinator;
import ru.nexora.network.PlayerTransferRecord;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.inventory.PlayerInventoryStorage;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerEquipmentStorage;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.world.PlayerDataStorage;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;
import ru.nexora.world.WorldItemEntity;
import ru.nexora.persistence.FilePlayerStateStore;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;

import java.nio.file.Path;
import java.nio.file.Files;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSessionManagerTest {
    @Test
    void mobAttackDeadlineSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE - 500L,
                BedrockSessionManager.mobAttackDeadline(Long.MAX_VALUE - 1_000L, 500L));
        assertEquals(Long.MAX_VALUE,
                BedrockSessionManager.mobAttackDeadline(Long.MAX_VALUE - 500L, 1_000L));
        assertEquals(1_500L, BedrockSessionManager.mobAttackDeadline(1_000L, 500L));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSessionManager.mobAttackDeadline(-1L, 500L));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSessionManager.mobAttackDeadline(1_000L, -1L));
    }

    @Test
    void outboundFramesRemainOrderedAcrossTransientTransportFailure() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        List<byte[]> delivered = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("outbound-order", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {
                    if (attempts.getAndIncrement() == 0) throw new java.io.IOException("temporary transport failure");
                    delivered.add(frame.clone());
                }, event -> {});
        try {
            manager.onIdentity("outbound-session", new NetherNetClientIdentity(
                    "outbound-token", new byte[] {1}, "outbound-xuid",
                    "OutboundPlayer", "outbound-playfab", "20CA2", null));
            java.lang.reflect.Method send = BedrockSessionManager.class.getDeclaredMethod(
                    "sendEncodedFrames", String.class, List.class);
            send.setAccessible(true);

            assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> send.invoke(manager, "outbound-session", List.of(new byte[] {1}, new byte[] {2})));
            send.invoke(manager, "outbound-session", List.of(new byte[] {3}));

            assertEquals(3, delivered.size());
            assertArrayEquals(new byte[] {1}, delivered.get(0));
            assertArrayEquals(new byte[] {2}, delivered.get(1));
            assertArrayEquals(new byte[] {3}, delivered.get(2));
            String summary = manager.adminRuntimeSummary();
            assertTrue(summary.contains("outboundFramesSent=3"));
            assertTrue(summary.contains("outboundSendFailures=1"));
            assertTrue(summary.contains("outboundQueuedFrames=0"));
            assertTrue(summary.contains("outboundQueuedBytes=0"));
            assertTrue(summary.contains("inboundAdmittedFrames="));
            assertTrue(summary.contains("inboundAdmittedBytes="));
            assertTrue(summary.contains("inboundRejectedFrames="));
            assertTrue(summary.contains("inboundTrackedPeers="));
            assertTrue(summary.contains("inboundReassemblyReservedBytes="));
            assertTrue(summary.contains("inboundReassemblyMaxBytes="));
            assertTrue(summary.contains("inboundReassemblyRejectedReservations="));
        } finally {
            manager.close();
        }
    }

    @Test
    void outboundDrainUsesFrameBudgetAndContinuesOnNextSessionPump() throws Exception {
        List<byte[]> delivered = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("outbound-budget", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> delivered.add(frame.clone()), event -> {});
        try {
            manager.onIdentity("outbound-budget-session", new NetherNetClientIdentity(
                    "outbound-budget-token", new byte[] {1}, "outbound-budget-xuid",
                    "OutboundBudgetPlayer", "outbound-budget-playfab", "20CA2", null));
            List<byte[]> frames = new ArrayList<>();
            for (int i = 0; i < 70; i++) frames.add(new byte[] {(byte) i});
            java.lang.reflect.Method send = BedrockSessionManager.class.getDeclaredMethod(
                    "sendEncodedFrames", String.class, List.class);
            send.setAccessible(true);
            send.invoke(manager, "outbound-budget-session", frames);

            assertEquals(64, delivered.size(), "One drain must respect the frame budget");
            assertEquals(6, (int) getSessionField(manager, "outbound-budget-session", "outboundQueue")
                    .getClass().getMethod("frameCount").invoke(
                            getSessionField(manager, "outbound-budget-session", "outboundQueue")));

            java.lang.reflect.Method pump = BedrockSessionManager.class.getDeclaredMethod(
                    "drainOutboundForSession", String.class);
            pump.setAccessible(true);
            pump.invoke(manager, "outbound-budget-session");

            assertEquals(70, delivered.size());
            for (int i = 0; i < 70; i++) assertArrayEquals(new byte[] {(byte) i}, delivered.get(i));
            assertTrue(manager.adminRuntimeSummary().contains("outboundQueuedFrames=0"));
        } finally {
            manager.close();
        }
    }

    @Test
    void outboundDrainAlsoRespectsByteBudget() throws Exception {
        List<Integer> deliveredSizes = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("outbound-byte-budget", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> deliveredSizes.add(frame.length), event -> {});
        try {
            manager.onIdentity("outbound-byte-session", new NetherNetClientIdentity(
                    "outbound-byte-token", new byte[] {1}, "outbound-byte-xuid",
                    "OutboundBytePlayer", "outbound-byte-playfab", "20CA2", null));
            List<byte[]> frames = new ArrayList<>();
            for (int i = 0; i < 5; i++) frames.add(new byte[BedrockSessionManager.SCTP_MAX_MESSAGE_BYTES]);
            java.lang.reflect.Method send = BedrockSessionManager.class.getDeclaredMethod(
                    "sendEncodedFrames", String.class, List.class);
            send.setAccessible(true);
            send.invoke(manager, "outbound-byte-session", frames);

            assertEquals(4, deliveredSizes.size(),
                    "The 1 MiB drain budget should stop before the fifth maximum-size frame");
            assertEquals(262_144, deliveredSizes.getFirst());
            java.lang.reflect.Method pump = BedrockSessionManager.class.getDeclaredMethod(
                    "drainOutboundForSession", String.class);
            pump.setAccessible(true);
            pump.invoke(manager, "outbound-byte-session");
            assertEquals(5, deliveredSizes.size());
            assertEquals(0, (int) getSessionField(manager, "outbound-byte-session", "outboundQueue")
                    .getClass().getMethod("frameCount").invoke(
                            getSessionField(manager, "outbound-byte-session", "outboundQueue")));
        } finally {
            manager.close();
        }
    }

    @Test
    void outboundGlobalBudgetRejectsReservationsBeyondConfiguredCap() throws Exception {
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("outbound-global-budget", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {}, event -> {});
        try {
            java.lang.reflect.Method reserve = BedrockSessionManager.class.getDeclaredMethod(
                    "reserveOutboundBytes", long.class);
            reserve.setAccessible(true);
            assertTrue((boolean) reserve.invoke(manager, 64L * 1024L * 1024L));
            assertFalse((boolean) reserve.invoke(manager, 1L));
            ((AtomicLong) getManagerField(manager, "outboundQueuedBytesGlobal")).set(0L);
        } finally {
            manager.close();
        }
    }

    @Test
    void adminRuntimeSummaryIncludesActualMobAiAndJvmResourceMetrics() throws Exception {
        ru.nexora.world.World testWorld = new ru.nexora.world.World(
                "admin-metrics-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                testWorld, (networkId, label, frame) -> {}, event -> {});
        manager.spawnLivingEntity("minecraft:zombie", 20.0f, 0.5, 65.0, 0.5);
        // A tick with no players still exercises a real, idle AI decision for passive mobs only;
        // hostile mobs with no target remain intentionally idle, so use a cow for a decision.
        manager.spawnLivingEntity("minecraft:cow", 20.0f, 0.5, 65.0, 0.5);
        invokeLivingActorTick(manager, 1L);
        ru.nexora.ai.NexoraAiDiagnostics diagnostics = new ru.nexora.ai.NexoraAiDiagnostics();
        manager.setAiDiagnostics(diagnostics);

        String summary = manager.adminRuntimeSummary();
        assertTrue(summary.contains("mobAiDecisions="));
        assertTrue(summary.contains("mobAiAvgNs="));
        assertTrue(summary.contains("mobAiMaxNs="));
        assertTrue(summary.contains("mobAiFailures="));
        assertTrue(summary.contains("heapUsed="));
        assertTrue(summary.contains("nonHeapUsed="));
        assertTrue(summary.contains("threads="));
        String mobSummary = manager.mobAiDiagnosticsSummary();
        assertTrue(mobSummary.contains("Mob AI decision algorithm: decisions="));
        assertTrue(mobSummary.contains(", total="));
        assertEquals(1, mobSummary.split("Mob spawners:", -1).length - 1,
                "Spawner metrics should appear once in the operator summary");
        manager.close();
    }

    @Test
    void reconcilesFrozenSourceSessionOnlyFromDurableAbortOrCommit() throws Exception {
        java.util.List<byte[]> frames = new java.util.ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("transfer-reconcile", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> frames.add(frame), event -> {});
        try {
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            manager.onIdentity("frozen-abort", new NetherNetClientIdentity(
                    "abort-token", new byte[] {1}, "abort-xuid",
                    "AbortPlayer", "abort-playfab", "20CA2", null));
            setSessionField(manager, "frozen-abort", "transferFrozen", true);
            setSessionField(manager, "frozen-abort", "loginAuthenticated", false);
            setSessionField(manager, "frozen-abort", "clientInitialized", false);
            UUID abortedId = UUID.randomUUID();
            ledger.prepare(new PlayerHandoff(abortedId, 1L, "node-a", "node-b", new byte[0]),
                    "abort-xuid", 1L, 10L);
            var abortedClaim = ledger.claim(abortedId, "node-b", 11L, 10_000L).orElseThrow();
            ledger.abort(abortedId, abortedClaim.fencingToken(), 12L).orElseThrow();

            var abortReport = manager.reconcileFrozenPlayerTransfers(ledger, "node-a", java.util.Map.of());
            assertEquals(1, abortReport.resumed());
            assertFalse((boolean) getSessionField(manager, "frozen-abort", "transferFrozen"));
            assertTrue((boolean) getSessionField(manager, "frozen-abort", "loginAuthenticated"));

            manager.onIdentity("frozen-commit", new NetherNetClientIdentity(
                    "commit-token", new byte[] {2}, "commit-xuid",
                    "CommitPlayer", "commit-playfab", "20CA2", null));
            setSessionField(manager, "frozen-commit", "transferFrozen", true);
            setSessionField(manager, "frozen-commit", "loginAuthenticated", false);
            setSessionField(manager, "frozen-commit", "clientInitialized", false);
            setSessionField(manager, "frozen-commit", "compressionNegotiated", true);
            UUID committedId = UUID.randomUUID();
            ledger.prepare(new PlayerHandoff(committedId, 2L, "node-a", "node-b", new byte[0]),
                    "commit-xuid", 2L, 20L);
            var committedClaim = ledger.claim(committedId, "node-b", 21L, 10_000L).orElseThrow();
            ledger.commit(committedId, committedClaim.fencingToken(), 22L).orElseThrow();

            var commitReport = manager.reconcileFrozenPlayerTransfers(ledger, "node-a",
                    java.util.Map.of("node-b", new java.net.InetSocketAddress("127.0.0.1", 19132)));
            assertEquals(1, commitReport.redirected());
            assertTrue((boolean) getSessionField(manager, "frozen-commit", "transferRedirected"));
            assertFalse(frames.isEmpty());
        } finally {
            manager.close();
        }
    }

    @Test
    void recoversFrozenSourceWhenTransferWasNeverPublishedAndLeaseIsStillOwned() throws Exception {
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("orphan-freeze", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {}, event -> {});
        try {
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            InMemoryPlayerOnlineLeaseRegistry leases = new InMemoryPlayerOnlineLeaseRegistry();
            manager.configureOnlinePlayerLeases(leases, 15_000L);
            manager.configureTransferOwnershipGuard(ledger, "node-a");
            long now = System.currentTimeMillis();
            assertTrue(leases.acquire("orphan-xuid", "node-a", "orphan-session", now, 15_000L));
            manager.onIdentity("orphan-session", new NetherNetClientIdentity(
                    "orphan-token", new byte[] {3}, "orphan-xuid",
                    "OrphanPlayer", "orphan-playfab", "20CA2", null));
            setSessionField(manager, "orphan-session", "transferFrozen", true);
            setSessionField(manager, "orphan-session", "loginAuthenticated", false);
            setSessionField(manager, "orphan-session", "clientInitialized", false);
            setSessionField(manager, "orphan-session", "onlineLeaseSessionId", "orphan-session");

            var report = manager.reconcileFrozenPlayerTransfers(ledger, "node-a", java.util.Map.of());

            assertEquals(1, report.scanned());
            assertEquals(1, report.resumed());
            assertEquals(0, report.unresolved());
            assertFalse((boolean) getSessionField(manager, "orphan-session", "transferFrozen"));
            assertTrue((boolean) getSessionField(manager, "orphan-session", "loginAuthenticated"));
            assertTrue(leases.renew("orphan-xuid", "node-a", "orphan-session",
                    System.currentTimeMillis(), 15_000L));
        } finally {
            manager.close();
        }
    }

    @Test
    void shutdownSurfacesPlayerPersistenceFailureAndKeepsOnlineLeaseFenced() throws Exception {
        Path playersDirectory = temporaryDirectory.resolve("shutdown-players");
        Files.createDirectories(playersDirectory);
        Files.createDirectory(playersDirectory.resolve("shutdown-xuid.nxp"));
        PlayerDataStorage profiles = new PlayerDataStorage(playersDirectory);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("shutdown-save-failure", 0L,
                        new ru.nexora.world.FlatWorldGenerator()),
                profiles, null, null, null, (networkId, label, frame) -> {}, event -> {});
        var leases = new InMemoryPlayerOnlineLeaseRegistry();
        manager.configureOnlinePlayerLeases(leases, 15_000L);
        assertTrue(leases.acquire("shutdown-xuid", "node-a", "shutdown-session",
                System.currentTimeMillis(), 15_000L));
        manager.onIdentity("shutdown-session", new NetherNetClientIdentity(
                "shutdown-token", new byte[] {7}, "shutdown-xuid",
                "ShutdownPlayer", "shutdown-playfab", "20CA2", null));
        setSessionField(manager, "shutdown-session", "onlineLeaseSessionId", "shutdown-session");
        setSessionField(manager, "shutdown-session", "loginAuthenticated", true);
        setSessionField(manager, "shutdown-session", "positionInitialized", true);

        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::close);
        assertTrue(failure.getMessage().contains("player persistence failures"));
        assertTrue(leases.renew("shutdown-xuid", "node-a", "shutdown-session",
                System.currentTimeMillis(), 15_000L),
                "A failed final save must not release ownership and permit a stale checkpoint takeover");
    }

    @Test
    void autosaveRetainsGlobalOnlineLeaseAndPersistsCanonicalCheckpoint() throws Exception {
        var checkpoints = new ru.nexora.persistence.PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("autosave-lease", 0L, new ru.nexora.world.FlatWorldGenerator()),
                null, null, null, checkpoints, (networkId, label, frame) -> {}, event -> {});
        var leases = new InMemoryPlayerOnlineLeaseRegistry();
        try {
            manager.configureOnlinePlayerLeases(leases, 15_000L);
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            manager.configureTransferOwnershipGuard(ledger, "node-a");
            long now = System.currentTimeMillis();
            assertTrue(leases.acquire("autosave-xuid", "node-a", "autosave-session", now, 15_000L));
            manager.onIdentity("autosave-session", new NetherNetClientIdentity(
                    "autosave-token", new byte[] {4}, "autosave-xuid",
                    "AutosavePlayer", "autosave-playfab", "20CA2", null));
            setSessionField(manager, "autosave-session", "onlineLeaseSessionId", "autosave-session");
            setSessionField(manager, "autosave-session", "loginAuthenticated", true);
            setSessionField(manager, "autosave-session", "clientInitialized", true);
            setSessionField(manager, "autosave-session", "positionInitialized", true);

            manager.savePlayers();

            assertTrue(leases.renew("autosave-xuid", "node-a", "autosave-session",
                    System.currentTimeMillis(), 15_000L),
                    "Periodic autosave must not release the player's global ownership lease");
            assertTrue(checkpoints.latestRevision("autosave-xuid") >= 0,
                    "Shared canonical state must be saved while the lease is held");
        } finally {
            manager.close();
        }
    }

    @Test
    void autosaveRejectsSessionAndSkipsCheckpointWhenGlobalLeaseIsLost() throws Exception {
        var checkpoints = new ru.nexora.persistence.PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        ru.nexora.network.PlayerOnlineLeaseRegistry lostLease = new ru.nexora.network.PlayerOnlineLeaseRegistry() {
            @Override public boolean acquire(String xuid, String nodeId, String sessionId, long now, long ttl) {
                return true;
            }
            @Override public boolean renew(String xuid, String nodeId, String sessionId, long now, long ttl) {
                return false;
            }
            @Override public boolean release(String xuid, String nodeId, String sessionId) {
                released.set(true);
                return true;
            }
        };
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("autosave-lost-lease", 0L, new ru.nexora.world.FlatWorldGenerator()),
                null, null, null, checkpoints, (networkId, label, frame) -> {}, event -> {});
        try {
            manager.configureOnlinePlayerLeases(lostLease, 15_000L);
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            manager.configureTransferOwnershipGuard(ledger, "node-a");
            manager.onIdentity("lost-lease-session", new NetherNetClientIdentity(
                    "lost-lease-token", new byte[] {5}, "lost-lease-xuid",
                    "LostLeasePlayer", "lost-lease-playfab", "20CA2", null));
            setSessionField(manager, "lost-lease-session", "onlineLeaseSessionId", "lost-lease-session");
            setSessionField(manager, "lost-lease-session", "loginAuthenticated", true);
            setSessionField(manager, "lost-lease-session", "clientInitialized", true);
            setSessionField(manager, "lost-lease-session", "positionInitialized", true);

            assertThrows(java.io.IOException.class, manager::savePlayers);

            assertTrue(released.get());
            assertTrue((boolean) getSessionField(manager, "lost-lease-session", "rejected"));
            assertFalse((boolean) getSessionField(manager, "lost-lease-session", "loginAuthenticated"));
            assertEquals(-1L, checkpoints.latestRevision("lost-lease-xuid"),
                    "A former owner must never overwrite the shared checkpoint after losing its lease");

            manager.onDisconnected("lost-lease-session");
            assertEquals(-1L, checkpoints.latestRevision("lost-lease-xuid"),
                    "Disconnect persistence must also skip writes after the lease was lost");
        } finally {
            manager.close();
        }
    }

    @Test
    void transferFreezeDoesNotCheckpointAfterGlobalOwnershipLeaseIsLost() throws Exception {
        var checkpoints = new ru.nexora.persistence.PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
        ru.nexora.network.PlayerOnlineLeaseRegistry lostLease = new ru.nexora.network.PlayerOnlineLeaseRegistry() {
            @Override public boolean acquire(String xuid, String nodeId, String sessionId, long now, long ttl) {
                return true;
            }
            @Override public boolean renew(String xuid, String nodeId, String sessionId, long now, long ttl) {
                return false;
            }
            @Override public boolean release(String xuid, String nodeId, String sessionId) {
                return true;
            }
        };
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("freeze-lost-lease", 0L, new ru.nexora.world.FlatWorldGenerator()),
                null, null, null, checkpoints, (networkId, label, frame) -> {}, event -> {});
        try {
            manager.configureOnlinePlayerLeases(lostLease, 15_000L);
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            manager.configureTransferOwnershipGuard(ledger, "node-a");
            manager.onIdentity("freeze-lost-session", new NetherNetClientIdentity(
                    "freeze-lost-token", new byte[] {6}, "freeze-lost-xuid",
                    "FreezeLostPlayer", "freeze-lost-playfab", "20CA2", null));
            setSessionField(manager, "freeze-lost-session", "onlineLeaseSessionId", "freeze-lost-session");
            setSessionField(manager, "freeze-lost-session", "loginAuthenticated", true);
            setSessionField(manager, "freeze-lost-session", "clientInitialized", true);
            setSessionField(manager, "freeze-lost-session", "positionInitialized", true);

            assertThrows(java.io.IOException.class,
                    () -> manager.freezePlayerForTransfer("freeze-lost-xuid"));

            assertFalse((boolean) getSessionField(manager, "freeze-lost-session", "transferFrozen"));
            assertEquals(-1L, checkpoints.latestRevision("freeze-lost-xuid"),
                    "A node without the global lease must not publish a new transfer checkpoint");
        } finally {
            // The simulated lease loss must fence persistence and make shutdown
            // report that the player's final state could not be safely checkpointed.
            assertThrows(IllegalStateException.class, manager::close);
        }
    }

    @Test
    void independentHeartbeatRenewsIdleLeaseAndFencesItWhenRenewalFails() throws Exception {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger renewals = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean renewalAllowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        ru.nexora.network.PlayerOnlineLeaseRegistry leases = new ru.nexora.network.PlayerOnlineLeaseRegistry() {
            @Override public boolean acquire(String xuid, String nodeId, String sessionId, long now, long ttl) {
                return true;
            }
            @Override public boolean renew(String xuid, String nodeId, String sessionId, long now, long ttl) {
                renewals.incrementAndGet();
                return renewalAllowed.get();
            }
            @Override public boolean release(String xuid, String nodeId, String sessionId) {
                releases.incrementAndGet();
                return true;
            }
        };
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("idle-heartbeat", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {}, event -> {}, nanos::get);
        try {
            manager.configureOnlinePlayerLeases(leases, 15_000L);
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            manager.configureTransferOwnershipGuard(ledger, "node-a");
            manager.onIdentity("idle-session", new NetherNetClientIdentity(
                    "idle-token", new byte[] {7}, "idle-xuid",
                    "IdlePlayer", "idle-playfab", "20CA2", null));
            setSessionField(manager, "idle-session", "onlineLeaseSessionId", "idle-session");
            setSessionField(manager, "idle-session", "loginAuthenticated", true);
            setSessionField(manager, "idle-session", "clientInitialized", true);

            manager.renewOnlinePlayerLeases();
            assertEquals(1, renewals.get());
            manager.renewOnlinePlayerLeases();
            assertEquals(1, renewals.get(), "Heartbeat throttles renewals to the configured lease interval");

            nanos.addAndGet(5_000_000_000L);
            manager.renewOnlinePlayerLeases();
            assertEquals(2, renewals.get(), "Idle sessions renew without client movement packets");

            renewalAllowed.set(false);
            nanos.addAndGet(5_000_000_000L);
            manager.renewOnlinePlayerLeases();
            assertEquals(3, renewals.get());
            assertEquals(1, releases.get());
            assertTrue((boolean) getSessionField(manager, "idle-session", "rejected"));
            assertFalse((boolean) getSessionField(manager, "idle-session", "loginAuthenticated"));
        } finally {
            assertThrows(IllegalStateException.class, manager::close,
                    "shutdown must surface the state that could not be persisted after lease loss");
        }
    }

    @Test
    void sourceRecoveryAbortsStaleUnclaimedPreparedTransferAndResumesPlayer() throws Exception {
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("stale-prepared-recovery", 0L,
                        new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {}, event -> {});
        try {
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            manager.onIdentity("stale-prepared-session", new NetherNetClientIdentity(
                    "stale-prepared-token", new byte[] {8}, "stale-prepared-xuid",
                    "StalePreparedPlayer", "stale-prepared-playfab", "20CA2", null));
            setSessionField(manager, "stale-prepared-session", "transferFrozen", true);
            setSessionField(manager, "stale-prepared-session", "loginAuthenticated", false);
            setSessionField(manager, "stale-prepared-session", "clientInitialized", false);
            UUID transferId = UUID.randomUUID();
            ledger.prepare(new PlayerHandoff(transferId, 1L, "node-a", "node-b", new byte[0]),
                    "stale-prepared-xuid", 1L, 1L);

            var report = manager.reconcileFrozenPlayerTransfers(ledger, "node-a", java.util.Map.of());

            assertEquals(1, report.resumed());
            assertEquals(0, report.unresolved());
            assertEquals(PlayerTransferRecord.State.ABORTED,
                    ledger.get(transferId).orElseThrow().state());
            assertFalse((boolean) getSessionField(manager, "stale-prepared-session", "transferFrozen"));
            assertTrue((boolean) getSessionField(manager, "stale-prepared-session", "loginAuthenticated"));
        } finally {
            manager.close();
        }
    }

    @Test
    void rejectsSecondActiveSessionForSameXuidOnOneNode() throws Exception {
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("duplicate-xuid", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {}, event -> {});
        try {
            manager.onIdentity("first-session", new NetherNetClientIdentity(
                    "first-token", new byte[] {1}, "same-xuid",
                    "Player", "first-playfab", "20CA2", null));
            manager.onIdentity("second-session", new NetherNetClientIdentity(
                    "second-token", new byte[] {2}, "same-xuid",
                    "Player", "second-playfab", "20CA2", null));
            setSessionField(manager, "first-session", "loginAuthenticated", true);

            java.lang.reflect.Method guard = BedrockSessionManager.class.getDeclaredMethod(
                    "ensureNoDuplicateXuidSession", String.class, String.class);
            guard.setAccessible(true);
            java.lang.reflect.InvocationTargetException failure = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> guard.invoke(manager, "second-session", "same-xuid"));
            assertInstanceOf(SecurityException.class, failure.getCause());
        } finally {
            manager.close();
        }
    }

    @Test
    void relaysAuthenticatedChatUsingServerOwnedPlayerName() throws Exception {
        java.util.List<String> recipients = new java.util.ArrayList<>();
        java.util.List<byte[]> frames = new java.util.ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("chat-test", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {
                    recipients.add(networkId);
                    frames.add(frame);
                }, event -> {});
        manager.onIdentity("chat-sender", new NetherNetClientIdentity(
                "sender-token", new byte[] {1}, "sender-xuid",
                "RealSender", "sender-playfab", "20CA2", null));
        manager.onIdentity("chat-recipient", new NetherNetClientIdentity(
                "recipient-token", new byte[] {2}, "recipient-xuid",
                "Recipient", "recipient-playfab", "20CA2", null));
        for (String id : java.util.List.of("chat-sender", "chat-recipient")) {
            setSessionField(manager, id, "compressionNegotiated", true);
            setSessionField(manager, id, "loginAuthenticated", true);
            setSessionField(manager, id, "clientInitialized", true);
        }

        BedrockPacket clientChat = BedrockTextCodec.createChatMessage(
                "SpoofedName", "Hello NEXORA", "sender-xuid", "");
        sendClientBatch(manager, "chat-sender",
                BedrockBatchCodec.encode(java.util.List.of(clientChat), true, 256));

        assertTrue(recipients.contains("chat-sender"));
        assertTrue(recipients.contains("chat-recipient"));
        boolean recipientReceivedChat = false;
        for (int i = 0; i < frames.size(); i++) {
            if (!"chat-recipient".equals(recipients.get(i))) continue;
            for (BedrockPacket outbound : decodeOutboundPackets(frames.get(i), true)) {
                if (outbound.packetId() != BedrockTextCodec.TEXT_PACKET_ID) continue;
                var decoded = BedrockTextCodec.decodeChat(outbound);
                assertEquals("RealSender", decoded.sourceName());
                assertEquals("Hello NEXORA", decoded.message());
                assertEquals("", decoded.xuid());
                recipientReceivedChat = true;
            }
        }
        assertTrue(recipientReceivedChat);
        manager.close();
    }

    @Test
    void limitsAuthenticatedChatToFiveMessagesPerFiveSecondWindow() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(2_000_000_000L);
        java.util.List<String> recipients = new java.util.ArrayList<>();
        java.util.List<byte[]> frames = new java.util.ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("chat-rate-limit-test", 0L,
                        new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {
                    recipients.add(networkId);
                    frames.add(frame);
                }, event -> {}, clock::get);
        manager.onIdentity("rate-limited-sender", new NetherNetClientIdentity(
                "rate-token", new byte[] {9}, "rate-xuid",
                "RateLimited", "rate-playfab", "20CA2", null));
        setSessionField(manager, "rate-limited-sender", "compressionNegotiated", true);
        setSessionField(manager, "rate-limited-sender", "loginAuthenticated", true);
        setSessionField(manager, "rate-limited-sender", "clientInitialized", true);

        for (int index = 0; index < 6; index++) {
            BedrockPacket chat = BedrockTextCodec.createChatMessage(
                    "RateLimited", "message-" + index, "rate-xuid", "");
            sendClientBatch(manager, "rate-limited-sender",
                    BedrockBatchCodec.encode(java.util.List.of(chat), true, 256));
            clock.addAndGet(100_000_000L);
        }

        long delivered = 0;
        for (int index = 0; index < frames.size(); index++) {
            if (!"rate-limited-sender".equals(recipients.get(index))) continue;
            delivered += decodeOutboundPackets(frames.get(index), true).stream()
                    .filter(packet -> packet.packetId() == BedrockTextCodec.TEXT_PACKET_ID).count();
        }
        assertEquals(5, delivered);
        manager.close();
    }

    @Test
    void rejectsChatWithForgedXboxIdentity() throws Exception {
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("chat-forgery-test", 0L, new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {}, event -> {});
        manager.onIdentity("chat-forgery", new NetherNetClientIdentity(
                "sender-token", new byte[] {1}, "real-xuid",
                "RealSender", "sender-playfab", "20CA2", null));
        setSessionField(manager, "chat-forgery", "compressionNegotiated", true);
        setSessionField(manager, "chat-forgery", "loginAuthenticated", true);
        setSessionField(manager, "chat-forgery", "clientInitialized", true);

        BedrockPacket forged = BedrockTextCodec.createChatMessage(
                "RealSender", "forged", "other-xuid", "");
        assertThrows(SecurityException.class, () -> sendClientBatch(manager, "chat-forgery",
                BedrockBatchCodec.encode(java.util.List.of(forged), true, 256)));
        manager.close();
    }

    @TempDir
    Path temporaryDirectory;
    @Test
    void breachSwordWeakensArmorDuringLivePlayerCombat() throws Exception {
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "breach-player-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {});
        manager.onIdentity("breach-attacker", new NetherNetClientIdentity(
                "breach-a-token", new byte[] {13}, "breach-a-xuid",
                "BreachAttacker", "playfab-breach-a", "20CA2", null));
        manager.onIdentity("breach-target", new NetherNetClientIdentity(
                "breach-t-token", new byte[] {14}, "breach-t-xuid",
                "BreachTarget", "playfab-breach-t", "20CA2", null));
        for (String id : List.of("breach-attacker", "breach-target")) {
            setSessionField(manager, id, "loginAuthenticated", true);
            setSessionField(manager, id, "clientInitialized", true);
            setSessionField(manager, id, "compressionNegotiated", true);
        }
        setSessionField(manager, "breach-attacker", "entityId", 113L);
        setSessionField(manager, "breach-attacker", "positionX", 0.0f);
        setSessionField(manager, "breach-attacker", "positionY", 65.0f);
        setSessionField(manager, "breach-attacker", "positionZ", 0.0f);
        setSessionField(manager, "breach-target", "entityId", 114L);
        setSessionField(manager, "breach-target", "positionX", 2.0f);
        setSessionField(manager, "breach-target", "positionY", 65.0f);
        setSessionField(manager, "breach-target", "positionZ", 0.0f);
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:breach", 4));
        manager.playerInventory("breach-attacker").orElseThrow().setSlot(0, sword);
        manager.playerEquipment("breach-target").orElseThrow().setArmor(1,
                new ItemStack("minecraft:iron_chestplate", 1, 1));
        var attack = new BedrockInventoryTransactionCodec.DecodedUseOnActor(
                114L, 1, 0, 0, java.util.Optional.of(sword),
                0.0f, 65.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        invokeActorAttack(manager, "breach-attacker", attack);

        assertEquals(12.768f,
                manager.playerVitals("breach-target").orElseThrow().health(), 0.0001f);
        manager.close();
    }

    @Test
    void densityMaceUsesServerFallDistanceWhenDamagingMob() throws Exception {
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "density-mace-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {});
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("density-attacker", new NetherNetClientIdentity(
                "density-token", new byte[] {11}, "density-xuid",
                "DensityAttacker", "playfab-density", "20CA2", null));
        setSessionField(manager, "density-attacker", "loginAuthenticated", true);
        setSessionField(manager, "density-attacker", "clientInitialized", true);
        setSessionField(manager, "density-attacker", "positionInitialized", true);
        setSessionField(manager, "density-attacker", "entityId", 111L);
        setSessionField(manager, "density-attacker", "positionX", 0.0f);
        setSessionField(manager, "density-attacker", "positionY", 65.0f);
        setSessionField(manager, "density-attacker", "positionZ", 0.0f);
        setSessionField(manager, "density-attacker", "physicsState",
                new ru.nexora.world.PlayerPhysics.State(
                        0, 65, 0, 0, -4, 0, false, 4.0, 0));
        ItemStack mace = new ItemStack("minecraft:mace", 1, 1,
                java.util.Map.of("minecraft:density", 3));
        manager.playerInventory("density-attacker").orElseThrow().setSlot(0, mace);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 2.0, 65.0, 0.0);
        var attack = new BedrockInventoryTransactionCodec.DecodedUseOnActor(
                zombie.runtimeId(), 1, 0, 0, java.util.Optional.of(mace),
                0.0f, 65.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        invokeActorAttack(manager, "density-attacker", attack);

        assertEquals(8.0f,
                combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow().health(), 0.0001f);
        manager.close();
    }

    @Test
    void lungeSpearJabPropelsAttackerAndConsumesExhaustion() throws Exception {
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "lunge-spear-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {});
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("lunge-attacker", new NetherNetClientIdentity(
                "lunge-token", new byte[] {12}, "lunge-xuid",
                "LungeAttacker", "playfab-lunge", "20CA2", null));
        setSessionField(manager, "lunge-attacker", "loginAuthenticated", true);
        setSessionField(manager, "lunge-attacker", "clientInitialized", true);
        setSessionField(manager, "lunge-attacker", "positionInitialized", true);
        setSessionField(manager, "lunge-attacker", "entityId", 112L);
        setSessionField(manager, "lunge-attacker", "positionX", 0.0f);
        setSessionField(manager, "lunge-attacker", "positionY", 65.0f);
        setSessionField(manager, "lunge-attacker", "positionZ", 0.0f);
        setSessionField(manager, "lunge-attacker", "physicsState",
                new ru.nexora.world.PlayerPhysics.State(0, 65, 0, 0, 0, 0, true, 0, 0));
        ItemStack spear = new ItemStack("minecraft:diamond_spear", 1, 1,
                java.util.Map.of("minecraft:lunge", 2));
        manager.playerInventory("lunge-attacker").orElseThrow().setSlot(0, spear);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 2.0, 65.0, 0.0);
        var attack = new BedrockInventoryTransactionCodec.DecodedUseOnActor(
                zombie.runtimeId(), 1, 0, 0, java.util.Optional.of(spear),
                0.0f, 65.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        invokeActorAttack(manager, "lunge-attacker", attack);

        var physics = (ru.nexora.world.PlayerPhysics.State) getSessionField(
                manager, "lunge-attacker", "physicsState");
        var vitals = manager.playerVitals("lunge-attacker").orElseThrow();
        assertEquals(18.32, physics.velocityZ(), 0.0001);
        assertEquals(3.0f, vitals.saturation(), 0.0001f);
        assertEquals(20, vitals.hunger());
        assertEquals(0.0f, vitals.exhaustion(), 0.0001f);

        // A second jab inside the diamond spear's 1.05 s cooldown is rejected.
        invokeActorAttack(manager, "lunge-attacker", attack);
        assertEquals(15.0f,
                combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow().health(), 0.0001f);
        var unchangedPhysics = (ru.nexora.world.PlayerPhysics.State) getSessionField(
                manager, "lunge-attacker", "physicsState");
        assertEquals(18.32, unchangedPhysics.velocityZ(), 0.0001);
        assertEquals(3.0f, manager.playerVitals("lunge-attacker").orElseThrow().saturation(), 0.0001f);
        manager.close();
    }

    @Test
    void serverCombatUsesConfiguredJabDamageForAllSevenSpearTiers() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(900_000_000L);
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "spear-tier-jab-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        manager.setItemRegistry(registry);
        manager.onIdentity("spear-tier-attacker", new NetherNetClientIdentity(
                "spear-tier-token", new byte[] {26}, "spear-tier-xuid",
                "SpearTierAttacker", "playfab-spear-tier", "20CA2", null));
        setSessionField(manager, "spear-tier-attacker", "loginAuthenticated", true);
        setSessionField(manager, "spear-tier-attacker", "clientInitialized", true);
        setSessionField(manager, "spear-tier-attacker", "compressionNegotiated", true);
        setSessionField(manager, "spear-tier-attacker", "positionInitialized", true);
        setSessionField(manager, "spear-tier-attacker", "entityId", 126L);
        setSessionField(manager, "spear-tier-attacker", "positionX", 0.5f);
        setSessionField(manager, "spear-tier-attacker", "positionY", 65.0f);
        setSessionField(manager, "spear-tier-attacker", "positionZ", 0.5f);

        String[] tiers = {
                "wooden", "golden", "stone", "copper", "iron", "diamond", "netherite"
        };
        float[] expectedDamage = {2.0f, 2.0f, 3.0f, 3.0f, 4.0f, 5.0f, 6.0f};
        for (int i = 0; i < tiers.length; i++) {
            String itemId = "minecraft:" + tiers[i] + "_spear";
            var definition = registry.findByIdentifier(itemId);
            assertNotNull(definition, itemId + " must be registered");
            manager.playerInventory("spear-tier-attacker").orElseThrow()
                    .setSlot(0, new ItemStack(itemId, 1, 1));
            var zombie = manager.spawnLivingEntity(
                    "minecraft:zombie", 20.0f, 0.5, 65.0, 3.5);
            sendActorAttack(manager, "spear-tier-attacker", zombie.runtimeId(),
                    definition.networkDefinition().runtimeItemId(), 0.5f, 65.0f, 0.5f);
            assertEquals(20.0f - expectedDamage[i],
                    combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow().health(),
                    0.0001f, tiers[i] + " spear jab damage");
            // Advance beyond the slowest tier cooldown before the next server hit.
            clock.addAndGet(1_200_000_000L);
        }
        manager.close();
    }

    @Test
    void protocol2225StartUseReleaseAndChargedSpearHitDamageMob() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "charged-spear-mob-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        manager.setItemRegistry(registry);
        manager.onIdentity("charge-mob-attacker", new NetherNetClientIdentity(
                "charge-mob-token", new byte[] {21}, "charge-mob-xuid",
                "ChargeMobAttacker", "playfab-charge-mob", "20CA2", null));
        setSessionField(manager, "charge-mob-attacker", "loginAuthenticated", true);
        setSessionField(manager, "charge-mob-attacker", "clientInitialized", true);
        setSessionField(manager, "charge-mob-attacker", "compressionNegotiated", true);
        setSessionField(manager, "charge-mob-attacker", "positionInitialized", true);
        setSessionField(manager, "charge-mob-attacker", "entityId", 121L);
        setSessionField(manager, "charge-mob-attacker", "positionX", 0.5f);
        setSessionField(manager, "charge-mob-attacker", "positionY", 65.0f);
        setSessionField(manager, "charge-mob-attacker", "positionZ", 0.5f);
        setSessionField(manager, "charge-mob-attacker", "lastMovementNanos", clock.get());
        setSessionField(manager, "charge-mob-attacker", "physicsState",
                new ru.nexora.world.PlayerPhysics.State(
                        0.5, 65.0, 0.5, 0.0, 0.0, 6.0, true, 0.0, 0.0));

        ItemStack spear = new ItemStack("minecraft:diamond_spear", 1, 1);
        manager.playerInventory("charge-mob-attacker").orElseThrow().setSlot(0, spear);
        var spearDefinition = registry.findByIdentifier("minecraft:diamond_spear");
        assertNotNull(spearDefinition);

        // Start the hold using the protocol-2225 PlayerAuthInputData value 52.
        BedrockPacket startUse = new BedrockPacket(
                BedrockPlayerAuthInputCodec.PLAYER_AUTH_INPUT_PACKET_ID, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(
                        0.5f, 65.0f, 0.5f, 0.0f, 1L, false,
                        BedrockPlayerAuthInputCodec.InputAction.START_USING_ITEM));
        sendClientBatch(manager, "charge-mob-attacker",
                BedrockBatchCodec.encode(List.of(startUse), true, 256));
        assertNotEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-mob-attacker", "spearChargeStartedNanos"));

        // Release the main-hand spear from the authenticated player position.
        clock.addAndGet(200_000_000L);
        BedrockPacket release = new BedrockPacket(
                BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID, 0, 0,
                itemReleasePayload(0, 0,
                        spearDefinition.networkDefinition().runtimeItemId(), 1,
                        0.5f, 65.0f, 0.5f));
        sendClientBatch(manager, "charge-mob-attacker",
                BedrockBatchCodec.encode(List.of(release), true, 256));
        assertEquals(clock.get(),
                getSessionField(manager, "charge-mob-attacker", "spearChargeReleasedNanos"));

        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 0.5, 65.0, 3.5);
        sendActorAttack(manager, "charge-mob-attacker", zombie.runtimeId(),
                spearDefinition.networkDefinition().runtimeItemId(), 0.5f, 65.0f, 0.5f);

        // 6 blocks/s exceeds the 4.6 blocks/s charge threshold:
        // diamond spear multiplier 1.075 gives 6.45 damage (base jab is 5).
        assertEquals(13.55f,
                combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow().health(), 0.0001f);
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-mob-attacker", "spearChargeStartedNanos"));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-mob-attacker", "spearChargeReleasedNanos"));

        // The released charge is single-use. After the normal spear cooldown,
        // another packet hit deals only the ordinary 5 damage, not charged damage.
        clock.addAndGet(1_100_000_000L);
        sendActorAttack(manager, "charge-mob-attacker", zombie.runtimeId(),
                spearDefinition.networkDefinition().runtimeItemId(), 0.5f, 65.0f, 0.5f);
        assertEquals(8.55f,
                combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow().health(), 0.0001f);
        manager.close();
    }

    @Test
    void protocol2225PlayerActionStartUseChargesSpearAgainstAnotherPlayer() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(2_000_000_000L);
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "charged-spear-pvp-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        List<byte[]> attackerFrames = new ArrayList<>();
        List<byte[]> targetFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {
                    if ("charge-pvp-attacker".equals(networkId)) attackerFrames.add(frame);
                    if ("charge-pvp-target".equals(networkId)) targetFrames.add(frame);
                }, event -> {}, clock::get);
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        manager.setItemRegistry(registry);
        manager.onIdentity("charge-pvp-attacker", new NetherNetClientIdentity(
                "charge-pvp-a-token", new byte[] {22}, "charge-pvp-a-xuid",
                "ChargePvpAttacker", "playfab-charge-pvp-a", "20CA2", null));
        manager.onIdentity("charge-pvp-target", new NetherNetClientIdentity(
                "charge-pvp-t-token", new byte[] {23}, "charge-pvp-t-xuid",
                "ChargePvpTarget", "playfab-charge-pvp-t", "20CA2", null));
        for (String id : List.of("charge-pvp-attacker", "charge-pvp-target")) {
            setSessionField(manager, id, "loginAuthenticated", true);
            setSessionField(manager, id, "clientInitialized", true);
            setSessionField(manager, id, "compressionNegotiated", true);
            setSessionField(manager, id, "positionInitialized", true);
        }
        setSessionField(manager, "charge-pvp-attacker", "entityId", 122L);
        setSessionField(manager, "charge-pvp-attacker", "positionX", 0.5f);
        setSessionField(manager, "charge-pvp-attacker", "positionY", 65.0f);
        setSessionField(manager, "charge-pvp-attacker", "positionZ", 0.5f);
        setSessionField(manager, "charge-pvp-attacker", "physicsState",
                new ru.nexora.world.PlayerPhysics.State(
                        0.5, 65.0, 0.5, 0.0, 0.0, 6.0, true, 0.0, 0.0));
        setSessionField(manager, "charge-pvp-target", "entityId", 123L);
        setSessionField(manager, "charge-pvp-target", "positionX", 0.5f);
        setSessionField(manager, "charge-pvp-target", "positionY", 65.0f);
        setSessionField(manager, "charge-pvp-target", "positionZ", 3.5f);
        setSessionField(manager, "charge-pvp-target", "physicsState",
                new ru.nexora.world.PlayerPhysics.State(
                        0.5, 65.0, 3.5, 0.0, 0.0, 0.0, true, 0.0, 0.0));

        ItemStack spear = new ItemStack("minecraft:diamond_spear", 1, 1);
        manager.playerInventory("charge-pvp-attacker").orElseThrow().setSlot(0, spear);
        var spearDefinition = registry.findByIdentifier("minecraft:diamond_spear");
        assertNotNull(spearDefinition);

        // PlayerAction 37 is StartUsingItem in protocol 2225.
        BedrockPacket startUse = new BedrockPacket(
                BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID, 0, 0,
                playerActionPayload(122L, BedrockPlayerActionCodec.ACTION_START_USING_ITEM));
        sendClientBatch(manager, "charge-pvp-attacker",
                BedrockBatchCodec.encode(List.of(startUse), true, 256));
        assertNotEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-pvp-attacker", "spearChargeStartedNanos"));

        clock.addAndGet(200_000_000L);
        BedrockPacket release = new BedrockPacket(
                BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID, 0, 0,
                itemReleasePayload(0, 0,
                        spearDefinition.networkDefinition().runtimeItemId(), 1,
                        0.5f, 65.0f, 0.5f));
        sendClientBatch(manager, "charge-pvp-attacker",
                BedrockBatchCodec.encode(List.of(release), true, 256));

        sendActorAttack(manager, "charge-pvp-attacker", 123L,
                spearDefinition.networkDefinition().runtimeItemId(), 0.5f, 65.0f, 0.5f);

        assertEquals(13.55f,
                manager.playerVitals("charge-pvp-target").orElseThrow().health(), 0.0001f);
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-pvp-attacker", "spearChargeStartedNanos"));
        List<BedrockPacket> observerPackets = targetFrames.stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(observerPackets.stream().anyMatch(packet ->
                        packet.packetId() == BedrockAnimateCodec.ANIMATE_PACKET_ID
                                && packet.payload().length > 0
                                && Byte.toUnsignedInt(packet.payload()[0]) == BedrockAnimateCodec.ACTION_SWING),
                "The target client must receive the attacker's main-hand swing animation");
        List<BedrockPacket> attackerPackets = attackerFrames.stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertFalse(attackerPackets.stream().anyMatch(packet ->
                        packet.packetId() == BedrockAnimateCodec.ANIMATE_PACKET_ID),
                "Do not replay the predicted swing to the attacking client");
        manager.close();
    }

    @Test
    void cancelledSpearUseAndChangedHeldItemDiscardTheCharge() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(2_500_000_000L);
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "spear-charge-cancel-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        manager.setItemRegistry(registry);
        manager.onIdentity("charge-cancel-player", new NetherNetClientIdentity(
                "charge-cancel-token", new byte[] {25}, "charge-cancel-xuid",
                "ChargeCancelPlayer", "playfab-charge-cancel", "20CA2", null));
        setSessionField(manager, "charge-cancel-player", "loginAuthenticated", true);
        setSessionField(manager, "charge-cancel-player", "clientInitialized", true);
        setSessionField(manager, "charge-cancel-player", "compressionNegotiated", true);
        setSessionField(manager, "charge-cancel-player", "positionInitialized", true);
        setSessionField(manager, "charge-cancel-player", "entityId", 125L);
        setSessionField(manager, "charge-cancel-player", "positionX", 0.5f);
        setSessionField(manager, "charge-cancel-player", "positionY", 65.0f);
        setSessionField(manager, "charge-cancel-player", "positionZ", 0.5f);

        ItemStack diamondSpear = new ItemStack("minecraft:diamond_spear", 1, 1);
        ItemStack woodenSpear = new ItemStack("minecraft:wooden_spear", 1, 1);
        manager.playerInventory("charge-cancel-player").orElseThrow().setSlot(0, diamondSpear);
        BedrockPacket startUse = new BedrockPacket(
                BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID, 0, 0,
                playerActionPayload(125L, BedrockPlayerActionCodec.ACTION_START_USING_ITEM));

        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(startUse), true, 256));
        assertNotEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeStartedNanos"));

        // ItemRelease action type 1 is an interrupted/cancelled use, not a completed charge.
        clock.addAndGet(100_000_000L);
        BedrockPacket cancelledRelease = new BedrockPacket(
                BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID, 0, 0,
                itemReleasePayload(1, 0, 856, 1, 0.5f, 65.0f, 0.5f));
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(cancelledRelease), true, 256));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeStartedNanos"));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeReleasedNanos"));

        // A second hold cannot be released with a different item than the captured stack.
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(startUse), true, 256));
        assertNotEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeStartedNanos"));
        clock.addAndGet(100_000_000L);
        manager.playerInventory("charge-cancel-player").orElseThrow().setSlot(0, woodenSpear);
        BedrockPacket changedItemRelease = new BedrockPacket(
                BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID, 0, 0,
                itemReleasePayload(0, 0, 861, 1, 0.5f, 65.0f, 0.5f));
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(changedItemRelease), true, 256));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeStartedNanos"));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeReleasedNanos"));

        // Moving the charged stack away and back through validated inventory transactions
        // must cancel the original hold; restoring the same stack cannot revive it.
        manager.playerInventory("charge-cancel-player").orElseThrow().setSlot(0, diamondSpear);
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(startUse), true, 256));
        assertNotEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeStartedNanos"));
        BedrockPacket moveSpearOut = new BedrockPacket(
                BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID, 0, 0,
                movePlayerInventorySlotPayload(0, 1, 856, 1));
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(moveSpearOut), true, 256));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeStartedNanos"));
        BedrockPacket moveSpearBack = new BedrockPacket(
                BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID, 0, 0,
                movePlayerInventorySlotPayload(1, 0, 856, 1));
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(moveSpearBack), true, 256));
        clock.addAndGet(100_000_000L);
        BedrockPacket staleRelease = new BedrockPacket(
                BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID, 0, 0,
                itemReleasePayload(0, 0, 856, 1, 0.5f, 65.0f, 0.5f));
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(staleRelease), true, 256));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeReleasedNanos"));

        // Charge timeout is enforced on PlayerAuthInput even without server-authoritative movement.
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(startUse), true, 256));
        assertNotEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeStartedNanos"));
        clock.addAndGet(10_100_000_000L);
        BedrockPacket movement = new BedrockPacket(
                BedrockPlayerAuthInputCodec.PLAYER_AUTH_INPUT_PACKET_ID, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(
                        0.5f, 65.0f, 0.5f, 0.0f, 1L, false));
        sendClientBatch(manager, "charge-cancel-player",
                BedrockBatchCodec.encode(List.of(movement), true, 256));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-cancel-player", "spearChargeStartedNanos"));
        manager.close();
    }

    @Test
    void changingHotbarSlotCancelsAnActiveSpearCharge() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(3_000_000_000L);
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "spear-charge-slot-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        manager.setItemRegistry(registry);
        manager.onIdentity("charge-slot-player", new NetherNetClientIdentity(
                "charge-slot-token", new byte[] {24}, "charge-slot-xuid",
                "ChargeSlotPlayer", "playfab-charge-slot", "20CA2", null));
        setSessionField(manager, "charge-slot-player", "loginAuthenticated", true);
        setSessionField(manager, "charge-slot-player", "clientInitialized", true);
        setSessionField(manager, "charge-slot-player", "compressionNegotiated", true);
        setSessionField(manager, "charge-slot-player", "positionInitialized", true);
        setSessionField(manager, "charge-slot-player", "entityId", 124L);
        setSessionField(manager, "charge-slot-player", "positionX", 0.5f);
        setSessionField(manager, "charge-slot-player", "positionY", 65.0f);
        setSessionField(manager, "charge-slot-player", "positionZ", 0.5f);
        ItemStack spear = new ItemStack("minecraft:diamond_spear", 1, 1);
        ItemStack alternate = new ItemStack("minecraft:wooden_spear", 1, 1);
        manager.playerInventory("charge-slot-player").orElseThrow().setSlot(0, spear);
        manager.playerInventory("charge-slot-player").orElseThrow().setSlot(1, alternate);

        BedrockPacket startUse = new BedrockPacket(
                BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID, 0, 0,
                playerActionPayload(124L, BedrockPlayerActionCodec.ACTION_START_USING_ITEM));
        sendClientBatch(manager, "charge-slot-player",
                BedrockBatchCodec.encode(List.of(startUse), true, 256));
        assertNotEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-slot-player", "spearChargeStartedNanos"));

        BedrockPacket selectOtherSlot = new BedrockPacket(0x1f, 0, 0,
                mobEquipmentPayload(124L, 861, 1, 1));
        sendClientBatch(manager, "charge-slot-player",
                BedrockBatchCodec.encode(List.of(selectOtherSlot), true, 256));
        assertEquals(1, manager.playerInventory("charge-slot-player").orElseThrow().selectedHotbarSlot());
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-slot-player", "spearChargeStartedNanos"));
        assertEquals(Long.MIN_VALUE,
                getSessionField(manager, "charge-slot-player", "spearChargeReleasedNanos"));
        manager.close();
    }

    @Test
    void appliesServerValidatedPlayerAttackWithSharpnessAndHurtImmunity() throws Exception {
        List<byte[]> outboundFrames = new ArrayList<>();
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "combat-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> outboundFrames.add(frame), event -> {});
        manager.onIdentity("combat-attacker", new NetherNetClientIdentity(
                "attacker-token", new byte[] {1}, "attacker-xuid", "Attacker", "playfab-a", "20CA2", null));
        manager.onIdentity("combat-target", new NetherNetClientIdentity(
                "target-token", new byte[] {2}, "target-xuid", "Target", "playfab-b", "20CA2", null));

        setSessionField(manager, "combat-attacker", "loginAuthenticated", true);
        setSessionField(manager, "combat-attacker", "clientInitialized", true);
        setSessionField(manager, "combat-attacker", "entityId", 100L);
        setSessionField(manager, "combat-attacker", "positionX", 0.0f);
        setSessionField(manager, "combat-attacker", "positionY", 65.0f);
        setSessionField(manager, "combat-attacker", "positionZ", 0.0f);
        setSessionField(manager, "combat-target", "loginAuthenticated", true);
        setSessionField(manager, "combat-target", "clientInitialized", true);
        setSessionField(manager, "combat-target", "entityId", 101L);
        setSessionField(manager, "combat-target", "positionX", 2.0f);
        setSessionField(manager, "combat-target", "positionY", 65.0f);
        setSessionField(manager, "combat-target", "positionZ", 0.0f);
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:sharpness", 3,
                        "minecraft:knockback", 2, "minecraft:fire_aspect", 1));
        manager.playerInventory("combat-attacker").orElseThrow().setSlot(0, sword);
        manager.playerEquipment("combat-target").orElseThrow().setArmor(1,
                new ItemStack("minecraft:diamond_chestplate", 1, 1,
                        java.util.Map.of("minecraft:thorns", 10)));
        var transaction = new BedrockInventoryTransactionCodec.DecodedUseOnActor(
                101L, 1, 0, 0, java.util.Optional.of(sword),
                0.0f, 65.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        combatWorld.setBlock(1, 66, 0, ru.nexora.world.BlockType.STONE);
        invokeActorAttack(manager, "combat-attacker", transaction);
        assertEquals(20.0f, manager.playerVitals("combat-target").orElseThrow().health(), 0.0001f);
        combatWorld.setBlock(1, 66, 0, ru.nexora.world.BlockType.AIR);

        invokeActorAttack(manager, "combat-attacker", transaction);
        assertEquals(12.01f, manager.playerVitals("combat-target").orElseThrow().health(), 0.0001f);
        float attackerHealth = manager.playerVitals("combat-attacker").orElseThrow().health();
        assertTrue(attackerHealth >= 16.0f && attackerHealth <= 19.0f,
                "Thorns X must reflect between one and four damage");
        assertTrue(outboundFrames.stream().flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockActorMotionCodec.SET_ACTOR_MOTION_PACKET_ID));
        assertNotEquals(Long.MIN_VALUE,
                getSessionField(manager, "combat-target", "fireUntilNanos"));
        assertFalse(outboundFrames.isEmpty());

        // Bedrock's short hurt-immunity window prevents packet spam from multiplying one hit.
        invokeActorAttack(manager, "combat-attacker", transaction);
        assertEquals(12.01f, manager.playerVitals("combat-target").orElseThrow().health(), 0.0001f);
        assertEquals(attackerHealth, manager.playerVitals("combat-attacker").orElseThrow().health(), 0.0001f);
        manager.close();
    }

    @Test
    void fireAspectTicksRespectFireProtectionAndExpire() throws Exception {
        AtomicLong clock = new AtomicLong();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("combat-world", 0L,
                        new ru.nexora.world.FlatWorldGenerator()),
                (networkId, label, frame) -> {}, event -> {}, clock::get);
        manager.onIdentity("fire-target", new NetherNetClientIdentity(
                "fire-token", new byte[] {3}, "fire-xuid", "FireTarget", "playfab-fire", "20CA2", null));
        setSessionField(manager, "fire-target", "loginAuthenticated", true);
        setSessionField(manager, "fire-target", "clientInitialized", true);
        setSessionField(manager, "fire-target", "entityId", 103L);
        setSessionField(manager, "fire-target", "fireUntilNanos", 5_000_000_000L);
        setSessionField(manager, "fire-target", "nextFireDamageNanos", 1_000_000_000L);
        manager.playerEquipment("fire-target").orElseThrow().setArmor(0,
                new ItemStack("minecraft:iron_chestplate", 1, 1,
                        java.util.Map.of("minecraft:fire_protection", 4)));

        clock.set(1_000_000_000L);
        invokeFireTick(manager, "fire-target", 1L);
        assertEquals(19.32f, manager.playerVitals("fire-target").orElseThrow().health(), 0.0001f);

        clock.set(2_000_000_000L);
        invokeFireTick(manager, "fire-target", 2L);
        assertEquals(18.64f, manager.playerVitals("fire-target").orElseThrow().health(), 0.0001f);

        clock.set(5_000_000_000L);
        invokeFireTick(manager, "fire-target", 3L);
        assertEquals(18.64f, manager.playerVitals("fire-target").orElseThrow().health(), 0.0001f);
        manager.close();
    }

    @Test
    void lethalThornsReflectionKillsMobAndAwardsDefenderLootAndExperience() throws Exception {
        AtomicLong clock = new AtomicLong(100_000_000L);
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "thorns-kill-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("thorns-killer", new NetherNetClientIdentity(
                "thorns-kill-token", new byte[] {8}, "thorns-kill-xuid",
                "ThornsKiller", "playfab-thorns-kill", "20CA2", null));
        setSessionField(manager, "thorns-killer", "loginAuthenticated", true);
        setSessionField(manager, "thorns-killer", "clientInitialized", true);
        setSessionField(manager, "thorns-killer", "positionInitialized", true);
        setSessionField(manager, "thorns-killer", "entityId", 108L);
        setSessionField(manager, "thorns-killer", "positionX", 1.0f);
        setSessionField(manager, "thorns-killer", "positionY", 65.0f);
        setSessionField(manager, "thorns-killer", "positionZ", 0.0f);
        manager.playerEquipment("thorns-killer").orElseThrow().setArmor(0,
                new ItemStack("minecraft:iron_chestplate", 1, 1,
                        java.util.Map.of("minecraft:thorns", 10)));

        var zombie = manager.spawnLivingEntity("minecraft:zombie", 1.0f, 0.0, 65.0, 0.0);
        invokeLivingActorTick(manager, 1L);

        assertTrue(combatWorld.livingEntities().find(zombie.runtimeId()).isEmpty());
        assertEquals(1, combatWorld.itemEntities().snapshot().size());
        assertEquals("minecraft:rotten_flesh",
                combatWorld.itemEntities().snapshot().getFirst().stack().itemId());
        long experienceMilli = ((Number) getSessionField(
                manager, "thorns-killer", "experiencePoints")).longValue() * 1000L
                + ((Number) getSessionField(
                        manager, "thorns-killer", "experienceRemainderMilli")).longValue();
        assertEquals(5_000L, experienceMilli);
        manager.close();
    }

    @Test
    void fireAspectKillRetainsPlayerCreditAndAwardsMobExperience() throws Exception {
        AtomicLong clock = new AtomicLong(100_000_000L);
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "mob-fire-reward-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("fire-killer", new NetherNetClientIdentity(
                "fire-reward-token", new byte[] {7}, "fire-reward-xuid",
                "FireKiller", "playfab-fire-reward", "20CA2", null));
        setSessionField(manager, "fire-killer", "loginAuthenticated", true);
        setSessionField(manager, "fire-killer", "clientInitialized", true);
        setSessionField(manager, "fire-killer", "positionInitialized", true);
        setSessionField(manager, "fire-killer", "entityId", 107L);
        setSessionField(manager, "fire-killer", "positionX", 0.0f);
        setSessionField(manager, "fire-killer", "positionY", 65.0f);
        setSessionField(manager, "fire-killer", "positionZ", 0.0f);

        ItemStack sword = new ItemStack("minecraft:wooden_sword", 1, 1,
                java.util.Map.of("minecraft:fire_aspect", 1, "minecraft:looting", 3));
        manager.playerInventory("fire-killer").orElseThrow().setSlot(0, sword);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 6.0f, 2.0, 65.0, 0.0);
        var attack = new BedrockInventoryTransactionCodec.DecodedUseOnActor(
                zombie.runtimeId(), 1, 0, 0, java.util.Optional.of(sword),
                0.0f, 65.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        invokeActorAttack(manager, "fire-killer", attack);
        assertEquals(1.0f,
                combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow().health(), 0.0001f);

        clock.addAndGet(1_000_000_000L);
        invokeLivingActorTick(manager, 2L);
        assertTrue(combatWorld.livingEntities().find(zombie.runtimeId()).isEmpty());
        assertEquals(1, combatWorld.itemEntities().snapshot().size());
        int delayedLootCount = combatWorld.itemEntities().snapshot().getFirst().stack().count();
        assertTrue(delayedLootCount >= 1 && delayedLootCount <= 4,
                "Delayed Fire Aspect death must retain Looting III kill credit");
        long experienceMilli = ((Number) getSessionField(
                manager, "fire-killer", "experiencePoints")).longValue() * 1000L
                + ((Number) getSessionField(
                        manager, "fire-killer", "experienceRemainderMilli")).longValue();
        assertEquals(5_000L, experienceMilli);
        manager.close();
    }

    @Test
    void killingHostileMobAwardsVanillaExperienceAndRemovesActor() throws Exception {
        List<byte[]> outboundFrames = new ArrayList<>();
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "mob-reward-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> outboundFrames.add(frame), event -> {});
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("reward-killer", new NetherNetClientIdentity(
                "reward-token", new byte[] {6}, "reward-xuid", "RewardKiller", "playfab-reward", "20CA2", null));
        setSessionField(manager, "reward-killer", "loginAuthenticated", true);
        setSessionField(manager, "reward-killer", "clientInitialized", true);
        setSessionField(manager, "reward-killer", "positionInitialized", true);
        setSessionField(manager, "reward-killer", "entityId", 106L);
        setSessionField(manager, "reward-killer", "positionX", 0.0f);
        setSessionField(manager, "reward-killer", "positionY", 65.0f);
        setSessionField(manager, "reward-killer", "positionZ", 0.0f);
        subscribeChunk(manager, "reward-killer", 0, 0);

        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:looting", 3));
        manager.playerInventory("reward-killer").orElseThrow().setSlot(0, sword);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 5.0f, 2.0, 65.0, 0.0);
        var attack = new BedrockInventoryTransactionCodec.DecodedUseOnActor(
                zombie.runtimeId(), 1, 0, 0, java.util.Optional.of(sword),
                0.0f, 65.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        invokeActorAttack(manager, "reward-killer", attack);

        assertTrue(combatWorld.livingEntities().find(zombie.runtimeId()).isEmpty());
        assertEquals(1, combatWorld.itemEntities().snapshot().size());
        assertEquals("minecraft:rotten_flesh",
                combatWorld.itemEntities().snapshot().getFirst().stack().itemId());
        int rottenFleshCount = combatWorld.itemEntities().snapshot().getFirst().stack().count();
        assertTrue(rottenFleshCount >= 1 && rottenFleshCount <= 4,
                "Looting III may add up to three rotten flesh");
        long experienceMilli = ((Number) getSessionField(
                manager, "reward-killer", "experiencePoints")).longValue() * 1000L
                + ((Number) getSessionField(
                        manager, "reward-killer", "experienceRemainderMilli")).longValue();
        assertEquals(5_000L, experienceMilli);
        assertTrue(outboundFrames.stream().flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID));
        manager.close();
    }

    @Test
    void hostileMobMovesTowardPlayerAndDealsMeleeDamage() throws Exception {
        AtomicLong clock = new AtomicLong(100_000_000L);
        List<byte[]> outboundFrames = new ArrayList<>();
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "hostile-ai-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> outboundFrames.add(frame),
                event -> {}, clock::get);
        manager.onIdentity("ai-player", new NetherNetClientIdentity(
                "ai-token", new byte[] {5}, "ai-xuid", "AiPlayer", "playfab-ai", "20CA2", null));
        setSessionField(manager, "ai-player", "loginAuthenticated", true);
        setSessionField(manager, "ai-player", "clientInitialized", true);
        setSessionField(manager, "ai-player", "positionInitialized", true);
        setSessionField(manager, "ai-player", "entityId", 105L);
        setSessionField(manager, "ai-player", "positionX", 4.0f);
        setSessionField(manager, "ai-player", "positionY", 65.0f);
        setSessionField(manager, "ai-player", "positionZ", 0.0f);
        subscribeChunk(manager, "ai-player", 0, 0);
        manager.playerEquipment("ai-player").orElseThrow().setArmor(0,
                new ItemStack("minecraft:iron_chestplate", 1, 1,
                        java.util.Map.of("minecraft:thorns", 10)));

        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 0.0, 65.0, 0.0);
        invokeLivingActorTick(manager, 1L);
        var moved = combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow();
        assertEquals(0.23, moved.x(), 0.0001);
        assertTrue(outboundFrames.stream().flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId()
                        == BedrockMoveActorAbsoluteCodec.MOVE_ACTOR_ABSOLUTE_PACKET_ID));

        // Bring the player into melee reach; the next world AI tick must damage them.
        setSessionField(manager, "ai-player", "positionX", 1.0f);
        clock.addAndGet(100_000_000L);
        invokeLivingActorTick(manager, 2L);
        assertEquals(17.72f, manager.playerVitals("ai-player").orElseThrow().health(), 0.0001f);
        float mobHealthAfterThorns = combatWorld.livingEntities()
                .find(zombie.runtimeId()).orElseThrow().health();
        assertTrue(mobHealthAfterThorns >= 16.0f && mobHealthAfterThorns <= 19.0f,
                "Thorns X must reflect between one and four damage to the attacking mob");
        assertTrue(outboundFrames.stream().flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId()
                        == BedrockActorMotionCodec.SET_ACTOR_MOTION_PACKET_ID));
        manager.close();
    }

    @Test
    void spawnsAndDamagesUndeadActorsWithSmiteFireAndKnockback() throws Exception {
        AtomicLong clock = new AtomicLong();
        List<byte[]> outboundFrames = new ArrayList<>();
        ru.nexora.world.World combatWorld = new ru.nexora.world.World(
                "mob-combat-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                combatWorld, (networkId, label, frame) -> outboundFrames.add(frame),
                event -> {}, clock::get);
        manager.onIdentity("mob-attacker", new NetherNetClientIdentity(
                "mob-token", new byte[] {4}, "mob-attacker-xuid", "MobAttacker", "playfab-mob", "20CA2", null));
        setSessionField(manager, "mob-attacker", "loginAuthenticated", true);
        setSessionField(manager, "mob-attacker", "clientInitialized", true);
        setSessionField(manager, "mob-attacker", "entityId", 104L);
        setSessionField(manager, "mob-attacker", "positionX", 0.0f);
        setSessionField(manager, "mob-attacker", "positionY", 65.0f);
        setSessionField(manager, "mob-attacker", "positionZ", 0.0f);
        subscribeChunk(manager, "mob-attacker", 0, 0);

        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:smite", 3,
                        "minecraft:knockback", 2, "minecraft:fire_aspect", 1));
        manager.playerInventory("mob-attacker").orElseThrow().setSlot(0, sword);
        var zombie = manager.spawnLivingEntity(
                "minecraft:zombie", 20.0f, 2.0, 65.0, 0.0);
        assertEquals(ru.nexora.world.WorldLivingEntity.Family.UNDEAD, zombie.family());
        assertTrue(outboundFrames.stream().flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddActorCodec.ADD_ACTOR_PACKET_ID));

        var attack = new BedrockInventoryTransactionCodec.DecodedUseOnActor(
                zombie.runtimeId(), 1, 0, 0, java.util.Optional.of(sword),
                0.0f, 65.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        invokeActorAttack(manager, "mob-attacker", attack);

        var damaged = combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow();
        assertEquals(4.5f, damaged.health(), 0.0001f); // diamond sword 8 + Smite III 7.5
        assertEquals(0.6, damaged.velocityX(), 0.0001);
        assertEquals(4_000L, damaged.fireUntilMillis());
        assertTrue(outboundFrames.stream().flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockActorMotionCodec.SET_ACTOR_MOTION_PACKET_ID));

        clock.set(1_000_000_000L);
        invokeLivingActorTick(manager, 1L);
        assertEquals(3.5f,
                combatWorld.livingEntities().find(zombie.runtimeId()).orElseThrow().health(), 0.0001f);
        assertTrue(manager.despawnLivingEntity(zombie.runtimeId()));
        assertTrue(combatWorld.livingEntities().find(zombie.runtimeId()).isEmpty());
        manager.close();
    }

    @Test
    void activeSpawnerCreatesAndReplicatesMobBeforeGenericAiScheduling() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);
        ru.nexora.world.World spawnerWorld = new ru.nexora.world.World(
                "spawner-ai-integration", 0L, new ru.nexora.world.FlatWorldGenerator());
        for (int chunkX = -1; chunkX <= 2; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 2; chunkZ++) spawnerWorld.getChunk(chunkX, chunkZ);
        }
        // This distant idle mob is deliberately deferred by the generic AI scheduler
        // on tick 200. Spawner processing must still run before that scheduling decision.
        spawnerWorld.livingEntities().spawn("minecraft:cow", 20, 40.5, 65.0, 40.5);
        spawnerWorld.livingEntities().spawn("minecraft:cow", 20, 41.5, 65.0, 40.5);
        List<byte[]> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                spawnerWorld, (networkId, label, frame) -> outbound.add(frame),
                event -> {}, clock::get);
        manager.onIdentity("spawner-player", new NetherNetClientIdentity(
                "spawner-token", new byte[] {15}, "spawner-xuid",
                "SpawnerPlayer", "playfab-spawner", "20CA2", null));
        setSessionField(manager, "spawner-player", "loginAuthenticated", true);
        setSessionField(manager, "spawner-player", "clientInitialized", true);
        setSessionField(manager, "spawner-player", "positionInitialized", true);
        setSessionField(manager, "spawner-player", "entityId", 115L);
        setSessionField(manager, "spawner-player", "positionX", 0.5f);
        setSessionField(manager, "spawner-player", "positionY", 65.0f);
        setSessionField(manager, "spawner-player", "positionZ", 0.5f);
        subscribeChunk(manager, "spawner-player", 0, 0);

        spawnerWorld.setBlock(0, 65, 0, ru.nexora.world.BlockType.SPAWNER);
        assertTrue(spawnerWorld.mobSpawners().configure(
                0, 65, 0, "minecraft:zombie", 20, 180));

        invokeLivingActorTick(manager, 200L);

        assertTrue(spawnerWorld.livingEntities().snapshot().stream()
                .anyMatch(entity -> entity.identifier().equals("minecraft:zombie")));
        assertTrue(outbound.stream().flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddActorCodec.ADD_ACTOR_PACKET_ID));
        ru.nexora.combat.MobAiLoadController scheduler =
                (ru.nexora.combat.MobAiLoadController) getManagerField(manager, "mobAiLoadController");
        assertTrue(scheduler.stats().deferredUpdates() > 0,
                "The distant idle mob should be deferred while the active spawner still produces");
        manager.close();
    }

    @Test
    void hostileMobsDespawnAfterLongAbsenceButPassiveMobsRemain() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "mob-natural-despawn-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 0.5, 65.0, 0.5);
        var cow = manager.spawnLivingEntity("minecraft:cow", 10.0f, 2.5, 65.0, 0.5);

        invokeLivingActorTick(manager, 20L);
        clock.set(31_000_000_000L);
        invokeLivingActorTick(manager, 620L);

        assertTrue(mobWorld.livingEntities().find(zombie.runtimeId()).isEmpty());
        assertTrue(mobWorld.livingEntities().find(cow.runtimeId()).isPresent());
        manager.close();
    }

    @Test
    void boggedArrowAppliesPoisonWhenItHitsAPlayer() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "bogged-poison-arrow-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        manager.onIdentity("bogged-player", new NetherNetClientIdentity(
                "bogged-token", new byte[] {21}, "bogged-xuid",
                "BoggedPlayer", "playfab-bogged", "20CA2", null));
        setSessionField(manager, "bogged-player", "loginAuthenticated", true);
        setSessionField(manager, "bogged-player", "clientInitialized", true);
        setSessionField(manager, "bogged-player", "positionInitialized", true);
        setSessionField(manager, "bogged-player", "entityId", 121L);
        setSessionField(manager, "bogged-player", "positionX", 5.5f);
        setSessionField(manager, "bogged-player", "positionY", 65.0f);
        setSessionField(manager, "bogged-player", "positionZ", 0.5f);
        var bogged = manager.spawnLivingEntity("minecraft:bogged", 16.0f, 0.5, 65.0, 0.5);

        @SuppressWarnings("unchecked")
        java.util.Map<Long, ru.nexora.combat.ArrowProjectile> arrows =
                (java.util.Map<Long, ru.nexora.combat.ArrowProjectile>)
                        getManagerField(manager, "mobArrows");
        @SuppressWarnings("unchecked")
        java.util.Set<Long> poisonArrows =
                (java.util.Set<Long>) getManagerField(manager, "poisonMobArrows");
        long arrowId = 5_000_001L;
        arrows.put(arrowId, new ru.nexora.combat.ArrowProjectile(
                -arrowId, arrowId, "mob:" + bogged.runtimeId(),
                4.5, 65.9, 0.5, 1.0, 0.0, 0.0, 0));
        poisonArrows.add(arrowId);

        clock.addAndGet(50_000_000L);
        invokeLivingActorTick(manager, 1L);

        assertTrue((long) getSessionField(manager, "bogged-player", "poisonUntilNanos") > clock.get());
        assertFalse(poisonArrows.contains(arrowId));
        manager.close();
    }

    @Test
    void rangedMobsUseSpeciesCooldownOnTheLiveBedrockPath() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "ranged-cooldown-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        mobWorld.getChunk(0, 0);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        try {
            var skeleton = manager.spawnLivingEntity(
                    "minecraft:skeleton", 20.0f, 0.5, 65.0, 0.5);
            var target = new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    "cooldown-target", 8.5, 65.0, 0.5, true);
            java.lang.reflect.Method rangedAttack = BedrockSessionManager.class.getDeclaredMethod(
                    "tickRangedMobBehavior",
                    ru.nexora.world.WorldLivingEntity.class,
                    ru.nexora.combat.LivingEntityAiService.PlayerTarget.class,
                    long.class);
            rangedAttack.setAccessible(true);

            assertEquals(true, rangedAttack.invoke(manager, skeleton, target, 1_000L));
            @SuppressWarnings("unchecked")
            java.util.Map<Long, Long> deadlines =
                    (java.util.Map<Long, Long>) getManagerField(manager, "nextMobAttackMillis");
            assertEquals(2_250L, deadlines.get(skeleton.runtimeId()));

            assertEquals(true, rangedAttack.invoke(manager, skeleton, target, 2_000L));
            assertEquals(2_250L, deadlines.get(skeleton.runtimeId()),
                    "A skeleton must not fire again before its 1,250 ms recovery expires");

            assertEquals(true, rangedAttack.invoke(manager, skeleton, target, 2_250L));
            assertEquals(3_500L, deadlines.get(skeleton.runtimeId()));
        } finally {
            manager.close();
        }
    }


    @Test
    void ghastFireballExplosionDamagesNearbyPlayerAndDestroysOrdinaryBlocks() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "ghast-explosion-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        mobWorld.getChunk(0, 0);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        try {
            manager.setItemRegistry(new BedrockItemRegistry(List.of(
                    new BedrockItemRegistry.Entry("minecraft:cobblestone",
                            new BedrockInventoryContentCodec.ItemDefinition(
                                    1, 0, 0, new byte[] {10, 0, 0, 0})))));
            manager.onIdentity("blast-player", new NetherNetClientIdentity(
                    "blast-token", new byte[] {33}, "blast-xuid",
                    "BlastPlayer", "playfab-blast", "20CA2", null));
            setSessionField(manager, "blast-player", "loginAuthenticated", true);
            setSessionField(manager, "blast-player", "clientInitialized", true);
            setSessionField(manager, "blast-player", "positionInitialized", true);
            setSessionField(manager, "blast-player", "entityId", 133L);
            setSessionField(manager, "blast-player", "positionX", 5.5f);
            setSessionField(manager, "blast-player", "positionY", 65.0f);
            setSessionField(manager, "blast-player", "positionZ", 0.5f);
            setSessionField(manager, "blast-player", "physicsState",
                    ru.nexora.world.PlayerPhysics.spawn(mobWorld, 5.5, 65.0, 0.5));
            var ghast = manager.spawnLivingEntity("minecraft:ghast", 16.0f, 10.0, 65.0, 0.5);
            var target = new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    "blast-player", 5.5, 65.0, 0.5, true);
            java.lang.reflect.Method launch = BedrockSessionManager.class.getDeclaredMethod(
                    "tickFireballMobBehavior",
                    ru.nexora.world.WorldLivingEntity.class,
                    ru.nexora.combat.LivingEntityAiService.PlayerTarget.class, long.class);
            launch.setAccessible(true);
            java.lang.reflect.Method tick = BedrockSessionManager.class.getDeclaredMethod(
                    "tickMobFireballs", long.class);
            tick.setAccessible(true);
            float initialHealth = manager.playerVitals("blast-player").orElseThrow().health();

            assertEquals(true, launch.invoke(manager, ghast, target, 1_000L));
            mobWorld.setBlock(6, 66, 0, ru.nexora.world.BlockType.STONE);
            mobWorld.setBlock(6, 67, 0, ru.nexora.world.BlockType.CHEST);
            for (int i = 0; i < 8; i++) tick.invoke(manager, (long) i + 1L);

            assertTrue(manager.playerVitals("blast-player").orElseThrow().health() < initialHealth);
            assertEquals(ru.nexora.world.BlockType.AIR, mobWorld.getBlock(6, 66, 0));
            assertEquals(ru.nexora.world.BlockType.CHEST, mobWorld.getBlock(6, 67, 0));
            assertTrue(mobWorld.itemEntities().snapshot().stream()
                    .anyMatch(item -> item.stack().itemId().equals("minecraft:cobblestone")),
                    "Stone destroyed by the blast must create a cobblestone world item");
            assertTrue((long) getSessionField(manager, "blast-player", "fireUntilNanos") > 0L);
            ru.nexora.world.PlayerPhysics.State blastedPhysics =
                    (ru.nexora.world.PlayerPhysics.State) getSessionField(manager, "blast-player", "physicsState");
            assertTrue(blastedPhysics.velocityY() > 0.0,
                    "Ghast blast must apply an upward impulse to nearby players");
        } finally {
            manager.close();
        }
    }

    @Test
    void ghastFireballTravelsAsFireballAndDamagesAndIgnitesBedrockPlayer() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "ghast-fireball-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        mobWorld.getChunk(0, 0);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        try {
            manager.onIdentity("fireball-player", new NetherNetClientIdentity(
                    "fireball-token", new byte[] {32}, "fireball-xuid",
                    "FireballPlayer", "playfab-fireball", "20CA2", null));
            setSessionField(manager, "fireball-player", "loginAuthenticated", true);
            setSessionField(manager, "fireball-player", "clientInitialized", true);
            setSessionField(manager, "fireball-player", "positionInitialized", true);
            setSessionField(manager, "fireball-player", "entityId", 132L);
            setSessionField(manager, "fireball-player", "positionX", 5.5f);
            setSessionField(manager, "fireball-player", "positionY", 65.0f);
            setSessionField(manager, "fireball-player", "positionZ", 0.5f);
            var ghast = manager.spawnLivingEntity(
                    "minecraft:ghast", 10.0f, 0.5, 65.0, 0.5);
            var target = new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    "fireball-player", 5.5, 65.0, 0.5, true);
            java.lang.reflect.Method launch = BedrockSessionManager.class.getDeclaredMethod(
                    "tickFireballMobBehavior",
                    ru.nexora.world.WorldLivingEntity.class,
                    ru.nexora.combat.LivingEntityAiService.PlayerTarget.class, long.class);
            launch.setAccessible(true);
            java.lang.reflect.Method tick = BedrockSessionManager.class.getDeclaredMethod(
                    "tickMobFireballs", long.class);
            tick.setAccessible(true);
            float initialHealth = manager.playerVitals("fireball-player").orElseThrow().health();

            assertEquals(true, launch.invoke(manager, ghast, target, 1_000L));
            for (int i = 0; i < 5; i++) tick.invoke(manager, (long) i + 1L);

            assertTrue(manager.playerVitals("fireball-player").orElseThrow().health() < initialHealth);
            assertTrue((long) getSessionField(manager, "fireball-player", "fireUntilNanos") > 0L);
        } finally {
            manager.close();
        }
    }

    @Test
    void guardianBeamChargesBeforeApplyingLongRangeDamage() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "guardian-beam-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        mobWorld.getChunk(0, 0);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        try {
            manager.onIdentity("guardian-player", new NetherNetClientIdentity(
                    "guardian-token", new byte[] {31}, "guardian-xuid",
                    "GuardianPlayer", "playfab-guardian", "20CA2", null));
            setSessionField(manager, "guardian-player", "loginAuthenticated", true);
            setSessionField(manager, "guardian-player", "clientInitialized", true);
            setSessionField(manager, "guardian-player", "positionInitialized", true);
            setSessionField(manager, "guardian-player", "entityId", 131L);
            setSessionField(manager, "guardian-player", "positionX", 8.5f);
            setSessionField(manager, "guardian-player", "positionY", 65.0f);
            setSessionField(manager, "guardian-player", "positionZ", 0.5f);
            var guardian = manager.spawnLivingEntity(
                    "minecraft:guardian", 30.0f, 0.5, 65.0, 0.5);
            var target = new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    "guardian-player", 8.5, 65.0, 0.5, true);
            java.lang.reflect.Method beam = BedrockSessionManager.class.getDeclaredMethod(
                    "tickGuardianBeamBehavior",
                    ru.nexora.world.WorldLivingEntity.class,
                    ru.nexora.combat.LivingEntityAiService.PlayerTarget.class,
                    long.class, long.class);
            beam.setAccessible(true);
            float initialHealth = manager.playerVitals("guardian-player").orElseThrow().health();

            assertEquals(true, beam.invoke(manager, guardian, target, 1_000L, 1L));
            assertEquals(initialHealth,
                    manager.playerVitals("guardian-player").orElseThrow().health(), 0.0001f);
            assertEquals(true, beam.invoke(manager, guardian, target, 2_500L, 2L));
            assertEquals(initialHealth - 5.0f,
                    manager.playerVitals("guardian-player").orElseThrow().health(), 0.0001f);
        } finally {
            manager.close();
        }
    }

    @Test
    void mobArrowCannotTunnelThroughAThinBlockBetweenTicks() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "arrow-sweep-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        mobWorld.setBlock(5, 65, 0, ru.nexora.world.BlockType.STONE);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<Long, ru.nexora.combat.ArrowProjectile> arrows =
                    (java.util.Map<Long, ru.nexora.combat.ArrowProjectile>)
                            getManagerField(manager, "mobArrows");
            long arrowId = 5_000_002L;
            arrows.put(arrowId, new ru.nexora.combat.ArrowProjectile(
                    -arrowId, arrowId, "mob:1",
                    4.5, 65.5, 0.5, 2.0, 0.0, 0.0, 0));

            java.lang.reflect.Method tickArrows = BedrockSessionManager.class.getDeclaredMethod(
                    "tickMobArrows", long.class);
            tickArrows.setAccessible(true);
            tickArrows.invoke(manager, 1L);

            assertFalse(arrows.containsKey(arrowId),
                    "The swept arrow segment crosses the stone block even though its endpoint is beyond it");
        } finally {
            manager.close();
        }
    }

    @Test
    void projectileChunkIndexesFilterDormantChunksAndFollowMovement() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "projectile-chunk-index-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        mobWorld.getChunk(0, 0);
        mobWorld.getChunk(1, 0);
        mobWorld.getChunk(5, 0);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        try {
            var nearbySkeleton = manager.spawnLivingEntity(
                    "minecraft:skeleton", 20.0f, 15.5, 65.0, 0.5);
            var distantSkeleton = manager.spawnLivingEntity(
                    "minecraft:skeleton", 20.0f, 80.5, 65.0, 0.5);
            java.lang.reflect.Method ranged = BedrockSessionManager.class.getDeclaredMethod(
                    "tickRangedMobBehavior",
                    ru.nexora.world.WorldLivingEntity.class,
                    ru.nexora.combat.LivingEntityAiService.PlayerTarget.class,
                    long.class);
            ranged.setAccessible(true);
            assertTrue((Boolean) ranged.invoke(manager, nearbySkeleton,
                    new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                            "near-target", 23.5, 65.0, 0.5, true), 1_000L));
            assertTrue((Boolean) ranged.invoke(manager, distantSkeleton,
                    new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                            "far-target", 88.5, 65.0, 0.5, true), 1_000L));

            long firstChunk = 0L;
            long fifthChunk = 5L << 32;
            assertEquals(1, manager.snapshotMobArrowsInChunkKeys(java.util.Set.of(firstChunk)).size());
            assertEquals(1, manager.snapshotMobArrowsInChunkKeys(java.util.Set.of(fifthChunk)).size());
            assertTrue(manager.snapshotMobArrowsInChunkKeys(java.util.Set.of(firstChunk))
                    .stream().noneMatch(arrow -> arrow.x() > 64.0),
                    "A recipient in chunk zero must not enumerate projectiles in distant chunks");

            java.lang.reflect.Method tickArrows = BedrockSessionManager.class.getDeclaredMethod(
                    "tickMobArrows", long.class);
            tickArrows.setAccessible(true);
            // Advance until the arrow actually crosses the 16-block chunk edge.
            // A single tick is not guaranteed to cover the remaining distance.
            for (int step = 0; step < 20
                    && manager.snapshotMobArrowsInChunkKeys(java.util.Set.of(firstChunk)).stream()
                    .anyMatch(arrow -> arrow.x() <= 16.0); step++) {
                tickArrows.invoke(manager, step + 1L);
            }

            assertTrue(manager.snapshotMobArrowsInChunkKeys(java.util.Set.of(1L << 32)).stream()
                    .anyMatch(arrow -> arrow.x() > 16.0),
                    "The projectile index must move the arrow into its new chunk");
            assertTrue(manager.snapshotMobArrowsInChunkKeys(java.util.Set.of(firstChunk)).isEmpty());

            @SuppressWarnings("unchecked")
            java.util.Map<String, java.util.Set<Long>> arrowsByOwner =
                    (java.util.Map<String, java.util.Set<Long>>) getManagerField(
                            manager, "mobArrowIdsByOwner");
            String ownerKey = "mob:" + nearbySkeleton.runtimeId();
            assertEquals(1, arrowsByOwner.get(ownerKey).size(),
                    "Each spawned projectile must be indexed by its owning mob");

            assertTrue(manager.despawnLivingEntity(nearbySkeleton.runtimeId()));
            assertTrue(manager.snapshotMobArrowsInChunkKeys(java.util.Set.of(1L << 32)).isEmpty(),
                    "Despawning a mob must also remove its projectiles from the chunk index");
            assertFalse(arrowsByOwner.containsKey(ownerKey),
                    "Despawning a mob must remove its owner index without scanning unrelated projectiles");
        } finally {
            manager.close();
        }
    }

    @Test
    void skeletonUsesItsSpeciesProjectileRangeBeforeLaunchingAnArrow() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "skeleton-projectile-range-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        mobWorld.getChunk(0, 0);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        try {
            var skeleton = manager.spawnLivingEntity(
                    "minecraft:skeleton", 20.0f, 0.5, 65.0, 0.5);
            var outsideRange = new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    "range-target", 13.5, 65.0, 0.5, true);
            var insideRange = new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    "range-target", 10.5, 65.0, 0.5, true);
            java.lang.reflect.Method ranged = BedrockSessionManager.class.getDeclaredMethod(
                    "tickRangedMobBehavior",
                    ru.nexora.world.WorldLivingEntity.class,
                    ru.nexora.combat.LivingEntityAiService.PlayerTarget.class,
                    long.class);
            ranged.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Long, ru.nexora.combat.ArrowProjectile> arrows =
                    (java.util.Map<Long, ru.nexora.combat.ArrowProjectile>)
                            getManagerField(manager, "mobArrows");

            assertFalse((Boolean) ranged.invoke(manager, skeleton, outsideRange, 2_000L));
            assertTrue(arrows.isEmpty(), "Skeleton must not shoot beyond its 12-block range");

            assertTrue((Boolean) ranged.invoke(manager, skeleton, insideRange, 2_100L));
            assertEquals(1, arrows.size(), "Skeleton should launch an arrow inside its species range");
        } finally {
            manager.close();
        }
    }

    @Test
    void caveSpiderAppliesPoisonButPoisonCannotKillPlayer() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "cave-spider-poison-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        manager.onIdentity("poison-player", new NetherNetClientIdentity(
                "poison-token", new byte[] {19}, "poison-xuid",
                "PoisonPlayer", "playfab-poison", "20CA2", null));
        setSessionField(manager, "poison-player", "loginAuthenticated", true);
        setSessionField(manager, "poison-player", "clientInitialized", true);
        setSessionField(manager, "poison-player", "positionInitialized", true);
        setSessionField(manager, "poison-player", "entityId", 119L);
        setSessionField(manager, "poison-player", "positionX", 1.0f);
        setSessionField(manager, "poison-player", "positionY", 65.0f);
        setSessionField(manager, "poison-player", "positionZ", 0.5f);
        var spider = manager.spawnLivingEntity("minecraft:cave_spider", 12.0f, 0.5, 65.0, 0.5);

        clock.addAndGet(50_000_000L);
        invokeLivingActorTick(manager, 1L);
        assertTrue((long) getSessionField(manager, "poison-player", "poisonUntilNanos") > clock.get());

        assertTrue(manager.despawnLivingEntity(spider.runtimeId()));
        setSessionField(manager, "poison-player", "vitals",
                new ru.nexora.world.PlayerVitals(1.0f, 20, 5.0f, 0.0f));
        clock.addAndGet(1_000_000_000L);
        invokeLivingActorTick(manager, 2L);

        ru.nexora.world.PlayerVitals vitals = (ru.nexora.world.PlayerVitals)
                getSessionField(manager, "poison-player", "vitals");
        assertEquals(1.0f, vitals.health(), 0.0001f);
        manager.close();
    }

    @Test
    void groundMobJumpsOverSingleBlockDuringAiTick() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "mob-jump-integration", 0L, new ru.nexora.world.FlatWorldGenerator());
        mobWorld.setBlock(1, 65, 0, ru.nexora.world.BlockType.STONE);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        manager.onIdentity("jump-player", new NetherNetClientIdentity(
                "jump-token", new byte[] {17}, "jump-xuid",
                "JumpPlayer", "playfab-jump", "20CA2", null));
        setSessionField(manager, "jump-player", "loginAuthenticated", true);
        setSessionField(manager, "jump-player", "clientInitialized", true);
        setSessionField(manager, "jump-player", "positionInitialized", true);
        setSessionField(manager, "jump-player", "entityId", 117L);
        setSessionField(manager, "jump-player", "positionX", 3.5f);
        setSessionField(manager, "jump-player", "positionY", 65.0f);
        setSessionField(manager, "jump-player", "positionZ", 0.5f);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 0.5, 65.0, 0.5);

        clock.addAndGet(50_000_000L);
        invokeLivingActorTick(manager, 1L);

        var jumping = mobWorld.livingEntities().find(zombie.runtimeId()).orElseThrow();
        assertEquals(ru.nexora.combat.MobJumpController.JUMP_VELOCITY,
                jumping.velocityY(), 0.0001);
        manager.close();
    }

    @Test
    void fallingMobTakesLandingDamageThroughWorldTick() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "mob-fall-damage-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 0.5, 70.0, 0.5);

        for (long tick = 0; tick < 30; tick++) {
            clock.addAndGet(50_000_000L);
            invokeLivingActorTick(manager, tick);
        }

        var landed = mobWorld.livingEntities().find(zombie.runtimeId()).orElseThrow();
        assertTrue(landed.y() <= 65.1 && landed.y() >= 64.9);
        assertTrue(landed.health() < 20.0f,
                "Fall tracking did not apply damage: " + manager.mobAiDiagnosticsSummary()
                        + "; mobY=" + landed.y() + "; velocityY=" + landed.velocityY());
        manager.close();
    }

    @Test
    void temporaryMobRetaliationTargetExpires() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "mob-aggro-expiry-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        var piglin = manager.spawnLivingEntity("minecraft:piglin", 16.0f, 0.5, 65, 0.5);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, String> targets =
                (java.util.Map<Long, String>) getManagerField(manager, "livingEntityAggroTargets");
        @SuppressWarnings("unchecked")
        java.util.Map<Long, Long> expiry =
                (java.util.Map<Long, Long>) getManagerField(manager, "livingEntityAggroExpiresAtMillis");
        targets.put(piglin.runtimeId(), "attacker");
        expiry.put(piglin.runtimeId(), 2_000L);

        java.lang.reflect.Method activeProvoker =
                BedrockSessionManager.class.getDeclaredMethod("activeMobProvoker", long.class, long.class);
        activeProvoker.setAccessible(true);
        assertEquals("attacker", activeProvoker.invoke(manager, piglin.runtimeId(), 1_999L));
        assertNull(activeProvoker.invoke(manager, piglin.runtimeId(), 2_000L));
        assertFalse(targets.containsKey(piglin.runtimeId()));
        assertFalse(expiry.containsKey(piglin.runtimeId()));
        manager.close();
    }

    @Test
    void fireDeathClearsTransientMobAiState() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(2_000_000_000L);
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "mob-fire-cleanup-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 1.0f, 0.5, 65.0, 0.5);
        assertTrue(mobWorld.livingEntities().ignite(zombie.runtimeId(), 5, 1_000).isPresent());

        @SuppressWarnings("unchecked")
        java.util.Map<Long, Long> attacks =
                (java.util.Map<Long, Long>) getManagerField(manager, "nextMobAttackMillis");
        @SuppressWarnings("unchecked")
        java.util.Map<Long, String> aggro =
                (java.util.Map<Long, String>) getManagerField(manager, "livingEntityAggroTargets");
        attacks.put(zombie.runtimeId(), 5_000L);
        aggro.put(zombie.runtimeId(), "test-player");
        @SuppressWarnings("unchecked")
        java.util.Map<Long, ru.nexora.combat.ArrowProjectile> arrows =
                (java.util.Map<Long, ru.nexora.combat.ArrowProjectile>) getManagerField(manager, "mobArrows");
        arrows.put(77L, new ru.nexora.combat.ArrowProjectile(
                -77L, 77L, "mob:" + zombie.runtimeId(),
                0.5, 66, 0.5, 0.1, 0.1, 0.1, 0));
        @SuppressWarnings("unchecked")
        java.util.Map<String, java.util.Set<Long>> arrowsByOwner =
                (java.util.Map<String, java.util.Set<Long>>) getManagerField(
                        manager, "mobArrowIdsByOwner");
        @SuppressWarnings("unchecked")
        java.util.Map<Long, String> arrowOwnerById =
                (java.util.Map<Long, String>) getManagerField(manager, "mobArrowOwnerById");
        String zombieOwnerKey = "mob:" + zombie.runtimeId();
        arrowsByOwner.computeIfAbsent(zombieOwnerKey, ignored -> new java.util.HashSet<>()).add(77L);
        arrowOwnerById.put(77L, zombieOwnerKey);
        ru.nexora.combat.MobFallTracker falls =
                (ru.nexora.combat.MobFallTracker) getManagerField(manager, "mobFallTracker");
        falls.update(zombie.runtimeId(), 80, false, false);
        ru.nexora.world.MobPathCache cache =
                (ru.nexora.world.MobPathCache) getManagerField(manager, "mobPathCache");
        ru.nexora.combat.MobStuckTracker stuck =
                (ru.nexora.combat.MobStuckTracker) getManagerField(manager, "mobStuckTracker");
        cache.findNextStep(zombie.runtimeId(), mobWorld, 0.5, 65, 0.5, 3.5, 0.5, 1_000);
        stuck.recordBlockedAttempt(zombie.runtimeId(), 0.5, 65, 0.5, 1_000);
        falls.update(zombie.runtimeId(), 80, false, false);

        invokeLivingActorTick(manager, 40L);

        assertTrue(mobWorld.livingEntities().find(zombie.runtimeId()).isEmpty());
        assertFalse(attacks.containsKey(zombie.runtimeId()));
        assertFalse(aggro.containsKey(zombie.runtimeId()));
        assertTrue(arrows.isEmpty());
        assertEquals(0, cache.size());
        assertEquals(0, stuck.stats(2_001).tracked());
        manager.close();
    }

    @Test
    void hostileMobNaturallyDespawnsAfterThirtySecondsFarFromPlayers() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "mob-natural-despawn-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {}, clock::get);
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 0.5, 65.0, 0.5);

        for (long tick = 0; tick <= 600; tick++) {
            clock.addAndGet(50_000_000L);
            invokeLivingActorTick(manager, tick);
        }

        assertTrue(mobWorld.livingEntities().find(zombie.runtimeId()).isEmpty());
        manager.close();
    }

    @Test
    void despawningMobClearsAllTransientAiState() throws Exception {
        ru.nexora.world.World mobWorld = new ru.nexora.world.World(
                "mob-cleanup-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                mobWorld, (networkId, label, frame) -> {}, event -> {});
        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 0.5, 65.0, 0.5);

        @SuppressWarnings("unchecked")
        java.util.Map<Long, Long> attacks =
                (java.util.Map<Long, Long>) getManagerField(manager, "nextMobAttackMillis");
        ru.nexora.combat.MobFuseTracker fuses =
                (ru.nexora.combat.MobFuseTracker) getManagerField(manager, "mobFuseTracker");
        @SuppressWarnings("unchecked")
        java.util.Map<Long, Long> teleports =
                (java.util.Map<Long, Long>) getManagerField(manager, "endermanTeleportCooldownMillis");
        @SuppressWarnings("unchecked")
        java.util.Map<Long, String> aggro =
                (java.util.Map<Long, String>) getManagerField(manager, "livingEntityAggroTargets");
        ru.nexora.combat.MobFallTracker falls =
                (ru.nexora.combat.MobFallTracker) getManagerField(manager, "mobFallTracker");
        falls.update(zombie.runtimeId(), 80, false, false);
        attacks.put(zombie.runtimeId(), 100L);
        assertFalse(fuses.isReadyToDetonate(zombie.runtimeId(), true, 100L, 1_500L));
        teleports.put(zombie.runtimeId(), 100L);
        aggro.put(zombie.runtimeId(), "test-player");
        @SuppressWarnings("unchecked")
        java.util.Map<Long, ru.nexora.combat.ArrowProjectile> arrows =
                (java.util.Map<Long, ru.nexora.combat.ArrowProjectile>) getManagerField(manager, "mobArrows");
        arrows.put(77L, new ru.nexora.combat.ArrowProjectile(
                -77L, 77L, "mob:" + zombie.runtimeId(),
                0.5, 66, 0.5, 0.1, 0.1, 0.1, 0));

        ru.nexora.world.MobPathCache cache =
                (ru.nexora.world.MobPathCache) getManagerField(manager, "mobPathCache");
        ru.nexora.combat.MobStuckTracker stuck =
                (ru.nexora.combat.MobStuckTracker) getManagerField(manager, "mobStuckTracker");
        cache.findNextStep(zombie.runtimeId(), mobWorld, 0.5, 65, 0.5, 3.5, 0.5, 1_000);
        stuck.recordBlockedAttempt(zombie.runtimeId(), 0.5, 65, 0.5, 1_000);

        assertTrue(manager.despawnLivingEntity(zombie.runtimeId()));
        assertFalse(attacks.containsKey(zombie.runtimeId()));
        assertEquals(0, fuses.trackedFuses());
        assertFalse(teleports.containsKey(zombie.runtimeId()));
        assertFalse(aggro.containsKey(zombie.runtimeId()));
        assertEquals(0, cache.size());
        assertEquals(0, stuck.stats(1_001).tracked());
        assertEquals(0, falls.stats().airborne());
        manager.close();
    }

    @Test
    void negotiatesNetworkSettingsThenDecodesCompressedGameBatch() {
        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicReference<BedrockSessionManager.PacketEvent> received = new AtomicReference<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225,
                (networkId, label, frame) -> outboundFrames.add(frame),
                received::set);

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "client-1", BedrockBatchCodec.encode(List.of(request), false, 256));

        assertEquals(1, outboundFrames.size());
        byte[] response = reassemble(outboundFrames);
        List<BedrockPacket> responsePackets = BedrockBatchCodec.decode(response, false);
        assertEquals(BedrockBatchCodec.NETWORK_SETTINGS_PACKET_ID, responsePackets.getFirst().packetId());
        assertEquals(1, manager.activeSessionCount());

        byte[] loginPayload = new byte[512];
        java.util.Arrays.fill(loginPayload, (byte) 0x42);
        BedrockPacket unknown = new BedrockPacket(0x7f, 0, 0, loginPayload);
        byte[] compressedLogin = BedrockBatchCodec.encode(List.of(unknown), true, 256);
        assertEquals(0, Byte.toUnsignedInt(compressedLogin[0]));
        sendClientBatch(manager, "client-1", compressedLogin);

        assertNotNull(received.get());
        assertEquals(0x7f, received.get().packet().packetId());
        assertArrayEquals(loginPayload, received.get().packet().payload());
    }

    @Test
    void sendsClientOutdatedPlayStatusForOlderProtocol() {
        List<byte[]> outboundFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, (networkId, label, frame) -> outboundFrames.add(frame), event -> {});

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2193).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "old-client", BedrockBatchCodec.encode(List.of(request), false, 256));

        BedrockPacket response = BedrockBatchCodec.decode(reassemble(outboundFrames), false).getFirst();
        assertEquals(BedrockBatchCodec.PLAY_STATUS_PACKET_ID, response.packetId());
        assertEquals(1, ByteBuffer.wrap(response.payload()).order(ByteOrder.BIG_ENDIAN).getInt());
    }

    @Test
    void requiresInitialSettingsRequestOnReliableChannel() {
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, (networkId, label, frame) -> {}, event -> {});
        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        byte[] batch = BedrockBatchCodec.encode(List.of(request), false, 256);
        byte[] frame = NetherNetMessageFramer.fragment(batch, 1200, false).getFirst();

        assertThrows(IllegalArgumentException.class,
                () -> manager.onFrame("client-2", "UnreliableDataChannel", frame));
    }

    @Test
    void rejectsLoginWhenPersistedInventoryIsCorruptedWithoutOverwritingIt() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();

        Path inventoryDirectory = temporaryDirectory.resolve("corrupt-inventories");
        Files.createDirectories(inventoryDirectory);
        Path inventoryFile = inventoryDirectory.resolve("corrupt-xuid.nxi");
        byte[] corruptedBytes = new byte[] {
                0x4e, 0x58, 0x49, 0x56, 0x01, 0x00, 0x24, 0x00, 0, 0, 0, 0
        };
        Files.write(inventoryFile, corruptedBytes);

        PlayerInventoryStorage inventoryStorage = new PlayerInventoryStorage(inventoryDirectory);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator()),
                null, inventoryStorage, (networkId, label, frame) -> {}, event -> {});
        manager.onIdentity("corrupt-client", new NetherNetClientIdentity(
                "corrupt-game-token", clientKey.getPublic().getEncoded(), "corrupt-xuid",
                "CorruptPlayer", "playfab-id", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "corrupt-client", BedrockBatchCodec.encode(List.of(request), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"AuthenticationType\":0,\"Token\":\"corrupt-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));

        assertThrows(SecurityException.class, () -> sendClientBatch(manager, "corrupt-client",
                BedrockBatchCodec.encode(List.of(login), true, 256)));
        assertEquals(0, manager.authenticatedPlayerCount());
        assertTrue(manager.playerInventory("corrupt-client").isEmpty());

        manager.onDisconnected("corrupt-client");
        assertArrayEquals(corruptedBytes, Files.readAllBytes(inventoryFile));
        manager.close();
    }

    @Test
    void sendsCraftingRecipesAndSettlesOnlyValidatedCraftingEvents() throws Exception {
        var world = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator(), 0, 65, 0);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        var inventoryStorage = new ru.nexora.inventory.PlayerInventoryStorage(
                temporaryDirectory.resolve("craft-inventory"));
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, null, inventoryStorage, null,
                (networkId, label, frame) -> outboundFrames.add(frame), event -> {}, () -> 0L);
        var itemRegistry = BedrockVanillaItemCatalog.createRegistry();
        manager.setItemRegistry(itemRegistry);
        manager.onIdentity("craft-client", new NetherNetClientIdentity(
                "craft-game-token", clientKey.getPublic().getEncoded(), "craft-xuid",
                "CraftPlayer", "craft-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\",\"AuthenticationType\":0,\"Token\":\"craft-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(login), true, 256));
        var inventory = manager.playerInventory("craft-client").orElseThrow();
        inventory.setSlot(0, new ItemStack("minecraft:iron_ingot", 1, 64));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0])
        ), true, 256));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));
        int beforeInit = outboundFrames.size();
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(
                List.of(new BedrockPacket(0x71, 0, 0, new byte[] {1})), true, 256));
        List<BedrockPacket> initialPackets = outboundFrames.subList(beforeInit, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(initialPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockCraftingDataCodec.CRAFTING_DATA_PACKET_ID));
        assertTrue(initialPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID
                        && Byte.toUnsignedInt(packet.payload()[0])
                        == BedrockInventoryContentCodec.PLAYER_UI_CONTAINER_ID));

        var recipes = ru.nexora.inventory.CraftingRecipeRegistry.vanillaSubset();
        var ironRecipe = recipes.find("minecraft:iron_nuggets_from_iron_ingot");
        var ironDefinition = itemRegistry.findByIdentifier("minecraft:iron_ingot").networkDefinition();
        var nuggetDefinition = itemRegistry.findByIdentifier("minecraft:iron_nugget").networkDefinition();
        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(0, 0, StackRef.of(ironDefinition, 1), StackRef.empty()),
                new SlotAction(124, 28, StackRef.empty(), StackRef.of(ironDefinition, 1)));
        BedrockPacket validCraft = new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEvent(ironRecipe.uuid(),
                        ironDefinition.runtimeItemId(), 1, nuggetDefinition.runtimeItemId(), 9));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(validCraft), true, 256));
        assertEquals(0, inventory.count("minecraft:iron_ingot"));
        assertEquals(9, inventory.count("minecraft:iron_nugget"));

        inventory.setSlot(2, new ItemStack("minecraft:oak_planks", 2, 64));
        var planksDefinition = itemRegistry.findByIdentifier("minecraft:oak_planks").networkDefinition();
        var sticksRecipe = recipes.find("minecraft:sticks_from_oak_planks");
        var stickDefinition = itemRegistry.findByIdentifier("minecraft:stick").networkDefinition();
        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(0, 2, StackRef.of(planksDefinition, 2), StackRef.of(planksDefinition, 1)),
                new SlotAction(124, 28, StackRef.empty(), StackRef.of(planksDefinition, 1)));
        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(0, 2, StackRef.of(planksDefinition, 1), StackRef.empty()),
                new SlotAction(124, 29, StackRef.empty(), StackRef.of(planksDefinition, 1)));

        BedrockPacket invalidHorizontalSticks = new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEventGrid(
                        sticksRecipe.uuid(), BedrockCraftingEventCodec.TYPE_SHAPED,
                        new int[] {planksDefinition.runtimeItemId(), planksDefinition.runtimeItemId(), 0, 0},
                        new int[] {1, 1, 0, 0},
                        new long[] {planksDefinition.blockRuntimeId(), planksDefinition.blockRuntimeId(), 0, 0},
                        stickDefinition.runtimeItemId(), 4, 0));
        sendClientBatch(manager, "craft-client",
                BedrockBatchCodec.encode(List.of(invalidHorizontalSticks), true, 256));
        assertEquals(0, inventory.count("minecraft:stick"));

        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(124, 29, StackRef.of(planksDefinition, 1), StackRef.empty()),
                new SlotAction(124, 30, StackRef.empty(), StackRef.of(planksDefinition, 1)));
        BedrockPacket validShapedCraft = new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEventGrid(
                        sticksRecipe.uuid(), BedrockCraftingEventCodec.TYPE_SHAPED,
                        new int[] {planksDefinition.runtimeItemId(), 0, planksDefinition.runtimeItemId(), 0},
                        new int[] {1, 0, 1, 0},
                        new long[] {planksDefinition.blockRuntimeId(), 0, planksDefinition.blockRuntimeId(), 0},
                        stickDefinition.runtimeItemId(), 4, 0));
        sendClientBatch(manager, "craft-client",
                BedrockBatchCodec.encode(List.of(validShapedCraft), true, 256));
        assertEquals(4, inventory.count("minecraft:stick"));
        assertEquals(0, inventory.count("minecraft:oak_planks"));

        inventory.setSlot(4, new ItemStack("minecraft:iron_ingot", 1, 64));
        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(0, 4, StackRef.of(ironDefinition, 1), StackRef.empty()),
                new SlotAction(124, 28, StackRef.empty(), StackRef.of(ironDefinition, 1)));
        var goldNuggetDefinition = itemRegistry.findByIdentifier("minecraft:gold_nugget").networkDefinition();
        BedrockPacket forgedOutput = new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEvent(ironRecipe.uuid(),
                        ironDefinition.runtimeItemId(), 1, goldNuggetDefinition.runtimeItemId(), 9));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(forgedOutput), true, 256));
        assertEquals(0, inventory.count("minecraft:iron_ingot"));
        assertEquals(9, inventory.count("minecraft:iron_nugget"));
        assertEquals(0, inventory.count("minecraft:gold_nugget"));
        manager.close();
        assertEquals(1, inventoryStorage.load("craft-xuid").orElseThrow().count("minecraft:iron_ingot"));
    }

    @Test
    void opensChestAndRoutesPersistedBedrockContainerTransactions() throws Exception {
        var containerStorage = new ContainerInventoryStorage(
                temporaryDirectory.resolve("chest-storage"), "NEXORA_World");
        var world = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator(),
                0, 65, 0, null, null, containerStorage);
        world.setBlock(1, 65, 0, ru.nexora.world.BlockType.CHEST);
        var chest = world.containers().orElseThrow().getOrCreate(1, 65, 0);
        chest.setSlot(0, new ItemStack("minecraft:diamond", 2, 64));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, null, null, null,
                (networkId, label, frame) -> outboundFrames.add(frame), event -> {}, () -> 0L);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("chest-client", new NetherNetClientIdentity(
                "chest-game-token", clientKey.getPublic().getEncoded(), "chest-xuid",
                "ChestPlayer", "chest-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\",\"AuthenticationType\":0,\"Token\":\"chest-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(login), true, 256));
        manager.playerInventory("chest-client").orElseThrow()
                .setSlot(0, new ItemStack("minecraft:chest", 1, 64));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0])
        ), true, 256));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(
                List.of(new BedrockPacket(0x71, 0, 0, new byte[] {1})), true, 256));

        int beforeOpen = outboundFrames.size();
        BedrockPacket clickChest = new BedrockPacket(0x90, 0, 0,
                chestUseMovementPayload(0.5f, 65.0f, 0.5f, 1L, 0, 54, 1));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(clickChest), true, 256));
        List<BedrockPacket> openPackets = outboundFrames.subList(beforeOpen, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        BedrockPacket open = openPackets.stream()
                .filter(packet -> packet.packetId() == BedrockContainerPacketCodec.CONTAINER_OPEN_PACKET_ID)
                .findFirst().orElseThrow();
        assertEquals(1, Byte.toUnsignedInt(open.payload()[0]));
        assertTrue(openPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID
                        && Byte.toUnsignedInt(packet.payload()[0]) == 1));

        BedrockPacket transfer = new BedrockPacket(0x1e, 0, 0, chestTransferPayload());
        int beforeTransfer = outboundFrames.size();
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(transfer), true, 256));
        assertTrue(chest.getSlot(0).isEmpty());
        assertEquals(2, manager.playerInventory("chest-client").orElseThrow().getSlot(1)
                .orElseThrow().count());
        List<BedrockPacket> transferPackets = outboundFrames.subList(beforeTransfer, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(transferPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID
                        && Byte.toUnsignedInt(packet.payload()[0]) == 1));

        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(
                BedrockContainerPacketCodec.createClose(1, 0, false)), true, 256));
        var persisted = containerStorage.load(1, 65, 0).orElseThrow();
        assertTrue(persisted.getSlot(0).isEmpty());
        assertEquals(2, manager.playerInventory("chest-client").orElseThrow()
                .getSlot(1).orElseThrow().count());
        manager.close();
    }

    @Test
    void consumesFoodOnlyAfterValidatedItemRelease() throws Exception {
        PlayerDataStorage profiles = new PlayerDataStorage(temporaryDirectory.resolve("food-profiles"));
        profiles.save(new PlayerData("food-xuid", "FoodPlayer", "NEXORA World",
                0.5f, 65.0f, 0.5f, 0, 0, 1,
                new PlayerVitals(20.0f, 10, 2.0f, 0.0f)));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        ru.nexora.world.World world = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator());
        WorldItemEntity existingItem = world.itemEntities().spawn(
                new ItemStack("minecraft:bread", 1, 64),
                2.5, 65.5, 2.5, 0, 0, 0, 0, 0, null);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, profiles, null, null,
                (networkId, label, frame) -> outboundFrames.add(frame), event -> {}, clock::get);
        manager.enableServerAuthoritativeMovement();
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("food-client", new NetherNetClientIdentity(
                "food-game-token", clientKey.getPublic().getEncoded(), "food-xuid",
                "FoodPlayer", "food-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\",\"AuthenticationType\":0,\"Token\":\"food-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(login), true, 256));
        manager.playerInventory("food-client").orElseThrow()
                .setSlot(0, new ItemStack("minecraft:bread", 1, 64));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0])
        ), true, 256));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        int beforeWorldStart = outboundFrames.size();
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(
                List.of(new BedrockPacket(0x71, 0, 0, new byte[] {1})), true, 256));

        assertEquals(10, manager.playerVitals("food-client").orElseThrow().hunger());
        assertTrue(outboundFrames.subList(beforeWorldStart, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddItemActorCodec.ADD_ITEM_ACTOR_PACKET_ID));
        assertTrue(world.itemEntities().find(existingItem.runtimeId()).isPresent());
        BedrockPacket startEating = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.foodUseMovementPayload(
                        0.5f, 65.0f, 0.5f, 0, 1L, 0, 261, 1,
                        0.5f, 65.0f, 0.5f));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(startEating), true, 256));
        assertEquals(1, manager.playerInventory("food-client").orElseThrow().count("minecraft:bread"));
        assertEquals(10, manager.playerVitals("food-client").orElseThrow().hunger());

        int beforeConsume = outboundFrames.size();
        BedrockPacket finishEating = new BedrockPacket(0x1e, 0, 0,
                itemReleasePayload(1, 0, 261, 1, 0.5f, 65.0f, 0.5f));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(finishEating), true, 256));
        assertEquals(0, manager.playerInventory("food-client").orElseThrow().count("minecraft:bread"));
        assertEquals(15, manager.playerVitals("food-client").orElseThrow().hunger());
        assertEquals(8.0f, manager.playerVitals("food-client").orElseThrow().saturation());
        assertTrue(outboundFrames.subList(beforeConsume, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockItemUseCompletionCodec.PACKET_ID));

        clock.set(1_000_000_000L);
        BedrockPacket startBreaking = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        0.5f, 65.0f, 0.5f, 0, 1, 64, 0, 1, 2L));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(startBreaking), true, 256));
        clock.set(1_600_000_000L);
        BedrockPacket stopBreaking = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        0.5f, 65.0f, 0.5f, 2, 1, 64, 0, 1, 3L));
        int beforeBlockDrop = outboundFrames.size();
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(stopBreaking), true, 256));
        assertEquals(ru.nexora.world.BlockType.AIR, world.getBlock(1, 64, 0));
        WorldItemEntity dirtDrop = world.itemEntities().snapshot().stream()
                .filter(entity -> entity.stack().itemId().equals("minecraft:dirt"))
                .findFirst().orElseThrow();
        assertTrue(outboundFrames.subList(beforeBlockDrop, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddItemActorCodec.ADD_ITEM_ACTOR_PACKET_ID));

        Thread.sleep(550);
        BedrockPacket takeDirt = BedrockTakeItemActorCodec.createAcknowledgement(
                dirtDrop.runtimeId(), manager.playerRuntimeId("food-client").orElseThrow());
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(takeDirt), true, 256));
        assertTrue(world.itemEntities().find(dirtDrop.runtimeId()).isEmpty());
        assertEquals(1, manager.playerInventory("food-client").orElseThrow().count("minecraft:dirt"));
        manager.close();
    }

    @Test
    void serverAuthoritativeMovementUsesInputAndCorrectsClientPrediction() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        ru.nexora.world.World world = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outboundFrames.add(frame), event -> {}, clock::get);
        manager.enableServerAuthoritativeMovement();
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("physics-client", new NetherNetClientIdentity(
                "physics-game-token", clientKey.getPublic().getEncoded(), "physics-xuid",
                "PhysicsPlayer", "physics-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\",\"AuthenticationType\":0,\"Token\":\"physics-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(login), true, 256));
        manager.playerInventory("physics-client").orElseThrow()
                .setSlot(0, new ItemStack("minecraft:stone", 2, 64));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0])
        ), true, 256));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(
                List.of(new BedrockPacket(0x71, 0, 0, new byte[] {1})), true, 256));

        completeChunkStreaming(manager, "physics-client", clock);
        int beforeMovement = outboundFrames.size();
        BedrockPacket forward = new BedrockPacket(0x90, 0, 0,
                movementInputPayload(0.5f, 65.0f, 0.715f, 0.0f, 1L, 0.0f, 1.0f));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(forward), true, 256));
        assertEquals(0.5f, manager.playerPosition("physics-client").orElseThrow().x(), 0.0001f);
        assertEquals(0.715f, manager.playerPosition("physics-client").orElseThrow().z(), 0.002f);
        assertEquals(beforeMovement, outboundFrames.size()); // client prediction agreed with server physics

        BedrockPacket forgedTeleport = new BedrockPacket(0x90, 0, 0,
                movementInputPayload(100.0f, 65.0f, 0.715f, 0.0f, 2L, 0.0f, 0.0f));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(forgedTeleport), true, 256));
        assertEquals(0.5f, manager.playerPosition("physics-client").orElseThrow().x(), 0.0001f);
        assertEquals(beforeMovement + 1, outboundFrames.size());
        BedrockPacket correction = decodeOutbound(outboundFrames.getLast(), true);
        assertEquals(BedrockMovementCorrectionCodec.PACKET_ID, correction.packetId());
        assertEquals(0, Byte.toUnsignedInt(correction.payload()[0]));

        int beforePlacement = outboundFrames.size();
        BedrockPacket placeStone = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.itemUseMovementPayload(
                        0.5f, 65.0f, 0.715f, 0.0f, 3L,
                        1, 64, 0, 1, 0, 1, 2,
                        Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.STONE)),
                        0.5f, 65.0f, 0.715f));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(placeStone), true, 256));
        assertEquals(ru.nexora.world.BlockType.STONE, world.getBlock(1, 65, 0));
        assertEquals(1, manager.playerInventory("physics-client").orElseThrow().count("minecraft:stone"));
        assertTrue(outboundFrames.subList(beforePlacement, outboundFrames.size()).stream()
                .map(frame -> decodeOutbound(frame, true).packetId())
                .anyMatch(id -> id == BedrockBlockUpdateCodec.UPDATE_BLOCK_PACKET_ID));

        assertEquals(20.0f, manager.playerVitals("physics-client").orElseThrow().health());
        assertEquals(20, manager.playerVitals("physics-client").orElseThrow().hunger());
        assertTrue(manager.damagePlayer("physics-client", 3.0f));
        assertEquals(17.0f, manager.playerVitals("physics-client").orElseThrow().health());
        clock.set(4_000_000_000L);
        BedrockPacket naturalRegeneration = new BedrockPacket(0x90, 0, 0,
                movementInputPayload(0.5f, 65.0f, 0.715f, 0.0f, 4L, 0.0f, 0.0f));
        sendClientBatch(manager, "physics-client",
                BedrockBatchCodec.encode(List.of(naturalRegeneration), true, 256));
        assertEquals(18.0f, manager.playerVitals("physics-client").orElseThrow().health());
        assertEquals(4.0f, manager.playerVitals("physics-client").orElseThrow().saturation());

        manager.playerInventory("physics-client").orElseThrow()
                .setSlot(1, new ItemStack("minecraft:cobblestone", 1, 64));
        manager.playerInventory("physics-client").orElseThrow().selectHotbarSlot(1);
        BedrockPacket placeCobblestone = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.itemUseMovementPayload(
                        0.5f, 65.0f, 0.715f, 0.0f, 5L,
                        2, 64, 2, 1, 1, 4, 1,
                        Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.COBBLESTONE)),
                        0.5f, 65.0f, 0.715f));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(placeCobblestone), true, 256));
        assertEquals(ru.nexora.world.BlockType.COBBLESTONE, world.getBlock(2, 65, 2));
        assertEquals(0, manager.playerInventory("physics-client").orElseThrow().count("minecraft:cobblestone"));
        manager.playerInventory("physics-client").orElseThrow().selectHotbarSlot(0);

        // Remove a small floor patch and let the server physics model land the player on the lower layer.
        for (int x = 0; x <= 0; x++) {
            for (int z = 0; z <= 1; z++) {
                for (int y = 60; y <= 64; y++) world.setBlock(x, y, z, ru.nexora.world.BlockType.AIR);
            }
        }
        assertEquals(ru.nexora.world.BlockType.AIR, world.getBlock(0, 64, 0));
        assertEquals(ru.nexora.world.BlockType.AIR, world.getBlock(0, 64, 1));
        world.setBlock(0, 59, 0, ru.nexora.world.BlockType.STONE);
        world.setBlock(0, 59, 1, ru.nexora.world.BlockType.STONE);
        for (long tick = 5; tick <= 40 && manager.playerVitals("physics-client").orElseThrow().health() == 18.0f; tick++) {
            BedrockPacket falling = new BedrockPacket(0x90, 0, 0,
                    movementInputPayload(0.5f, 65.0f, 0.715f, 0.0f, tick, 0.0f, 0.0f));
            sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(falling), true, 256));
        }
        assertTrue(manager.playerPosition("physics-client").orElseThrow().y() < 65.0f);
        assertEquals(16.0f, manager.playerVitals("physics-client").orElseThrow().health());
        world.setBlock(0, 60, 0, ru.nexora.world.BlockType.BEDROCK);
        world.setBlock(0, 61, 0, ru.nexora.world.BlockType.STONE);
        world.setBlock(0, 62, 0, ru.nexora.world.BlockType.DIRT);
        world.setBlock(0, 63, 0, ru.nexora.world.BlockType.DIRT);
        world.setBlock(0, 64, 0, ru.nexora.world.BlockType.GRASS_BLOCK);
        world.setBlock(0, 60, 1, ru.nexora.world.BlockType.BEDROCK);
        world.setBlock(0, 61, 1, ru.nexora.world.BlockType.STONE);
        world.setBlock(0, 62, 1, ru.nexora.world.BlockType.DIRT);
        world.setBlock(0, 63, 1, ru.nexora.world.BlockType.DIRT);
        world.setBlock(0, 64, 1, ru.nexora.world.BlockType.GRASS_BLOCK);

        int beforeDeath = outboundFrames.size();
        assertTrue(manager.damagePlayer("physics-client", 100.0f));
        assertEquals(0.0f, manager.playerVitals("physics-client").orElseThrow().health());
        List<BedrockPacket> deathPackets = outboundFrames.subList(beforeDeath, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(deathPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockRespawnCodec.DEATH_INFO_PACKET_ID));
        assertTrue(deathPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockRespawnCodec.RESPAWN_PACKET_ID));

        long runtimeEntityId = manager.playerRuntimeId("physics-client").orElseThrow();
        BedrockPacket respawnAction = new BedrockPacket(
                BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID, 0, 0,
                playerActionPayload(runtimeEntityId, BedrockPlayerActionCodec.ACTION_RESPAWN));
        int beforeRespawn = outboundFrames.size();
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(respawnAction), true, 256));
        assertEquals(PlayerVitals.DEFAULT, manager.playerVitals("physics-client").orElseThrow());
        assertEquals(0.5f, manager.playerPosition("physics-client").orElseThrow().x(), 0.0001f);
        assertEquals(65.0f, manager.playerPosition("physics-client").orElseThrow().y(), 0.0001f);
        List<BedrockPacket> respawnPackets = outboundFrames.subList(beforeRespawn, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(respawnPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockRespawnCodec.MOVE_PLAYER_PACKET_ID));
        manager.close();
    }

    @Test
    void authenticatesLoginAndCompletesEmptyResourcePackExchange() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();

        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicLong monotonicClock = new AtomicLong();
        PlayerDataStorage playerStorage = new PlayerDataStorage(temporaryDirectory.resolve("players"));
        PlayerInventoryStorage inventoryStorage = new PlayerInventoryStorage(temporaryDirectory.resolve("inventories"));
        PlayerEquipmentStorage equipmentStorage = new PlayerEquipmentStorage(temporaryDirectory.resolve("equipment"));
        ru.nexora.world.World testWorld = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225,
                "1.26.62",
                BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                testWorld,
                playerStorage,
                inventoryStorage,
                equipmentStorage,
                (networkId, label, frame) -> outboundFrames.add(frame),
                event -> {},
                monotonicClock::get);
        List<BedrockItemRegistry.Entry> itemEntries = new ArrayList<>(
                BedrockVanillaItemCatalog.createRegistry().entries());
        itemEntries.add(new BedrockItemRegistry.Entry("nexora:arcane_shard",
                new BedrockInventoryContentCodec.ItemDefinition(257, 0, 0, new byte[0])));
        manager.setItemRegistry(new BedrockItemRegistry(itemEntries));
        manager.setArmorSlotRegistry(ru.nexora.inventory.VanillaArmorSlotCatalog.createRegistry());
        manager.setEquipmentSlotRegistry(ru.nexora.inventory.NexoraMagicEquipmentCatalog.createRegistry());
        manager.onIdentity("authenticated-client", new NetherNetClientIdentity(
                "game-server-token",
                clientKey.getPublic().getEncoded(),
                "123456789",
                "TestPlayer",
                "playfab-id",
                "20CA2",
                null
        ));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(request), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"AuthenticationType\":0,\"Token\":\"game-server-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(login), true, 256));

        assertEquals(1, manager.authenticatedPlayerCount());
        var inventory = manager.playerInventory("authenticated-client").orElseThrow();
        assertEquals(36, inventory.size());
        assertTrue(inventory.heldItem().isEmpty());
        inventory.setSlot(0, new ItemStack("nexora:arcane_shard", 3, 64));
        inventory.setSlot(1, new ItemStack("nexora:arcane_shard", 1, 64));
        inventory.setSlot(2, new ItemStack("minecraft:iron_chestplate", 1, 1));
        manager.playerEquipment("authenticated-client").orElseThrow()
                .setArmor(0, new ItemStack("minecraft:iron_helmet", 1, 1));
        manager.playerEquipment("authenticated-client").orElseThrow()
                .setOffhand(new ItemStack("nexora:arcane_shard", 2, 64));
        manager.playerEquipment("authenticated-client").orElseThrow()
                .setMagicSlot(ru.nexora.inventory.EquipmentSlot.MAGIC_STAFF,
                        new ItemStack("nexora:ember_staff", 1, 1));
        manager.playerEquipment("authenticated-client").orElseThrow()
                .setMagicSlot(ru.nexora.inventory.EquipmentSlot.TALISMAN,
                        new ItemStack("nexora:luck_talisman", 1, 1));
        var equipmentStats = manager.playerEquipmentStats("authenticated-client").orElseThrow();
        assertEquals(12.0, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MAGIC_POWER), 0.0001);
        assertEquals(20.0, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MAX_MANA), 0.0001);
        assertEquals(1.0, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MANA_REGENERATION), 0.0001);
        assertEquals(0.10, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.COOLDOWN_REDUCTION), 0.0001);
        assertEquals(2.0, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.ARMOR), 0.0001);
        assertEquals(4, manager.playerInventory("authenticated-client").orElseThrow()
                .count("nexora:arcane_shard"));
        assertTrue(manager.playerInventory("unknown-client").isEmpty());

        BedrockPacket clientHandshake = new BedrockPacket(
                BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0]);
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(clientHandshake), true, 256));

        assertEquals(3, outboundFrames.size());
        assertEquals(BedrockBatchCodec.PLAY_STATUS_PACKET_ID,
                decodeOutbound(outboundFrames.get(1), true).packetId());
        assertEquals(BedrockBatchCodec.RESOURCE_PACKS_INFO_PACKET_ID,
                decodeOutbound(outboundFrames.get(2), true).packetId());

        BedrockPacket allPacksDownloaded = resourcePackResponse(2, "downloadingfinished");
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(allPacksDownloaded), true, 256));
        assertEquals(BedrockBatchCodec.RESOURCE_PACK_STACK_PACKET_ID,
                decodeOutbound(outboundFrames.get(3), true).packetId());

        BedrockPacket stackCompleted = resourcePackResponse(3, "resourcepackstackfinished");
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(stackCompleted), true, 256));
        assertEquals(1, manager.resourcePackReadyCount());
        assertEquals(13, outboundFrames.size());
        assertEquals(0x0b, decodeOutbound(outboundFrames.get(4), true).packetId());
        List<BedrockPacket> startGameBatch = BedrockBatchCodec.decode(
                reassemble(List.of(outboundFrames.get(4))), true);
        assertEquals(2, startGameBatch.size());
        assertEquals(BedrockItemRegistryPacketCodec.ITEM_REGISTRY_PACKET_ID,
                startGameBatch.get(1).packetId());
        assertEquals(0x0a, decodeOutbound(outboundFrames.get(5), true).packetId());
        assertEquals(0x3c, decodeOutbound(outboundFrames.get(6), true).packetId());
        assertEquals(0x3b, decodeOutbound(outboundFrames.get(7), true).packetId());
        assertEquals(0x2b, decodeOutbound(outboundFrames.get(8), true).packetId());
        assertEquals(0x3e, decodeOutbound(outboundFrames.get(9), true).packetId());
        assertEquals(0x79, decodeOutbound(outboundFrames.get(10), true).packetId());
        assertEquals(0x7a, decodeOutbound(outboundFrames.get(11), true).packetId());
        List<BedrockPacket> biomeAndRadius = BedrockBatchCodec.decode(
                reassemble(List.of(outboundFrames.get(11))), true);
        assertEquals(BedrockChunkRadiusCodec.UPDATED_PACKET_ID, biomeAndRadius.get(1).packetId());
        List<BedrockPacket> initialChunkPackets = new ArrayList<>();
        for (int index = 12; index < 13; index++) {
            initialChunkPackets.addAll(decodeOutboundPackets(outboundFrames.get(index), true));
        }
        assertEquals(4, initialChunkPackets.stream()
                .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                .count());

        // The server must not announce the player as spawned until the client confirms StartGame.
        BedrockPacket initialized = new BedrockPacket(0x71, 0, 0, new byte[] {1});
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(initialized), true, 256));
        assertEquals(16, outboundFrames.size());
        assertEquals(0x31, decodeOutbound(outboundFrames.get(14), true).packetId());
        List<BedrockPacket> initialInventoryBatch = BedrockBatchCodec.decode(
                reassemble(List.of(outboundFrames.get(14))), true);
        assertEquals(8, initialInventoryBatch.size());
        assertEquals(0x1f, initialInventoryBatch.get(1).packetId());
        assertEquals(0x31, initialInventoryBatch.get(2).packetId()); // armor container
        assertEquals(0x31, initialInventoryBatch.get(3).packetId()); // offhand container
        assertEquals(0x32, initialInventoryBatch.get(4).packetId()); // cursor slot
        assertEquals(BedrockAvailableCommandsCodec.AVAILABLE_COMMANDS_PACKET_ID,
                initialInventoryBatch.get(5).packetId()); // /equipment command
        assertEquals(BedrockCraftingDataCodec.CRAFTING_DATA_PACKET_ID,
                initialInventoryBatch.get(6).packetId()); // crafting recipes
        assertEquals(124, Byte.toUnsignedInt(initialInventoryBatch.get(7).payload()[0])); // player UI grid
        BedrockPacket playStatus = decodeOutbound(outboundFrames.get(13), true);
        assertEquals(BedrockBatchCodec.PLAY_STATUS_PACKET_ID, playStatus.packetId());
        assertEquals(3, ByteBuffer.wrap(playStatus.payload()).order(ByteOrder.BIG_ENDIAN).getInt());
        assertEquals(0.5f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(65.0f, manager.playerPosition("authenticated-client").orElseThrow().y());

        BedrockPacket selectSecondHotbarSlot = new BedrockPacket(0x1f, 0, 0,
                mobEquipmentPayload(1, 257, 1, 1));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(selectSecondHotbarSlot), true, 256));
        assertEquals(1, inventory.selectedHotbarSlot());
        assertEquals(1, inventory.heldItem().orElseThrow().count());

        monotonicClock.set(100_000_000L);
        BedrockPacket movement = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(1.0f, 65.0f, 1.0f, 30.0f, 0L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(movement), true, 256));
        BedrockSessionManager.PlayerPosition moved = manager.playerPosition("authenticated-client").orElseThrow();
        assertEquals(1.0f, moved.x());
        assertEquals(1.0f, moved.z());
        assertEquals(30.0f, moved.yaw());
        assertEquals(0L, moved.tick());

        // A second position update with the same client tick is a replay and must be ignored.
        BedrockPacket replayedMovement = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(2.0f, 65.0f, 1.0f, 30.0f, 0L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(replayedMovement), true, 256));
        assertEquals(1.0f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(17, outboundFrames.size());

        monotonicClock.set(200_000_000L);
        BedrockPacket implausibleTeleport = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(100.0f, 65.0f, 100.0f, 90.0f, 2L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(implausibleTeleport), true, 256));
        assertEquals(1.0f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(17, outboundFrames.size());

        float[] walkPositions = {3.0f, 5.0f, 7.0f, 9.0f, 11.0f, 13.0f, 15.0f, 16.5f};
        long[] walkTimes = {400_000_000L, 650_000_000L, 900_000_000L, 1_150_000_000L,
                1_400_000_000L, 1_650_000_000L, 1_900_000_000L, 2_100_000_000L};
        for (int i = 0; i < walkPositions.length; i++) {
            monotonicClock.set(walkTimes[i]);
            BedrockPacket step = new BedrockPacket(0x90, 0, 0,
                    BedrockPlayerAuthInputCodecTest.movementPayload(
                            walkPositions[i], 65.0f, 1.0f, 30.0f, 3L + i, false));
            sendClientBatch(manager, "authenticated-client",
                    BedrockBatchCodec.encode(List.of(step), true, 256));
            if (i < walkPositions.length - 1) assertEquals(18, outboundFrames.size());
        }
        assertEquals(20, outboundFrames.size());
        assertEquals(0x79, decodeOutbound(outboundFrames.get(18), true).packetId());
        List<BedrockPacket> newlyStreamedChunks = decodeOutboundPackets(outboundFrames.get(19), true);
        assertEquals(1, newlyStreamedChunks.stream()
                .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                .count(), "The first bounded batch contains one newly exposed column");
        completeChunkStreaming(manager, "authenticated-client", monotonicClock);
        List<BedrockPacket> remainingChunkBatch = decodeOutboundPackets(outboundFrames.get(20), true);
        assertEquals(2, remainingChunkBatch.stream()
                .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                .count());
        assertEquals(21, outboundFrames.size());
        assertEquals(16.5f, manager.playerPosition("authenticated-client").orElseThrow().x());

        monotonicClock.set(2_110_000_000L);
        BedrockPacket speedHack = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(26.5f, 65.0f, 1.0f, 30.0f, 11L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(speedHack), true, 256));
        assertEquals(16.5f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(21, outboundFrames.size());

        BedrockPacket invalidBlockPosition = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 0, 16, 400, 1, 1, 11L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(invalidBlockPosition), true, 256));
        assertEquals(21, outboundFrames.size());

        monotonicClock.set(3_100_000_000L);
        BedrockPacket startBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 0, 16, 64, 1, 1, 12L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(startBreak), true, 256));
        assertEquals(21, outboundFrames.size());

        monotonicClock.set(3_600_000_000L);
        BedrockPacket prematureBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 26, 16, 64, 1, 1, 5_000L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(prematureBreak), true, 256));
        assertEquals(21, outboundFrames.size()); // too little break time has elapsed

        monotonicClock.set(3_800_000_000L);
        BedrockPacket finishBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 26, 16, 64, 1, 1, 5_001L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(finishBreak), true, 256));
        assertEquals(24, outboundFrames.size());
        assertEquals(0x15, decodeOutbound(outboundFrames.get(21), true).packetId());

        // A decoded no-op player-slot transaction is accepted and reconciled with authoritative content.
        BedrockPacket inventoryTransaction = new BedrockPacket(0x1e, 0, 0,
                noOpInventoryTransactionPayload(257, 3));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(inventoryTransaction), true, 256));
        assertEquals(25, outboundFrames.size());
        assertEquals(0x31, decodeOutbound(outboundFrames.get(24), true).packetId());
        List<BedrockPacket> inventoryReconciliation = BedrockBatchCodec.decode(
                reassemble(List.of(outboundFrames.get(24))), true);
        assertEquals(5, inventoryReconciliation.size());
        assertEquals(0x1f, inventoryReconciliation.get(1).packetId());
        assertEquals(0x31, inventoryReconciliation.get(2).packetId());
        assertEquals(0x31, inventoryReconciliation.get(3).packetId());
        assertEquals(0x32, inventoryReconciliation.get(4).packetId());

        // Transfer one item from the main inventory into the offhand container.
        BedrockPacket offhandTransfer = new BedrockPacket(0x1e, 0, 0,
                transferToOffhandPayload(257, 1, 1));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(offhandTransfer), true, 256));
        assertEquals(3, manager.playerInventory("authenticated-client").orElseThrow()
                .count("nexora:arcane_shard"));
        assertEquals(3, manager.playerEquipment("authenticated-client").orElseThrow()
                .offhand().orElseThrow().count());
        assertEquals(26, outboundFrames.size());

        // Equip a registered iron chestplate from the main inventory into armor slot 1.
        BedrockPacket armorTransfer = new BedrockPacket(0x1e, 0, 0,
                transferToArmorPayload(347, 1, 2, 1));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(armorTransfer), true, 256));
        assertTrue(manager.playerInventory("authenticated-client").orElseThrow().getSlot(2).isEmpty());
        assertEquals(new ItemStack("minecraft:iron_chestplate", 1, 1),
                manager.playerEquipment("authenticated-client").orElseThrow().armor(1).orElseThrow());
        assertEquals(27, outboundFrames.size());

        // Open the player-facing equipment UI, choose the staff slot, and equip a compatible item.
        inventory.setSlot(4, new ItemStack("minecraft:gold_nugget", 1, 64));
        BedrockPacket equipmentCommand = new BedrockPacket(0x4d, 0, 0,
                commandRequestPayload("/equipment", "Player", "equipment-ui-1", 1L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(equipmentCommand), true, 256));
        assertEquals(28, outboundFrames.size());
        assertEquals(BedrockModalFormCodec.MODAL_FORM_REQUEST_PACKET_ID,
                decodeOutbound(outboundFrames.get(27), true).packetId());

        BedrockPacket chooseStaffSlot = new BedrockPacket(0x65, 0, 0,
                modalFormResponsePayload(1, "1"));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(chooseStaffSlot), true, 256));
        assertEquals(29, outboundFrames.size());
        assertEquals(BedrockModalFormCodec.MODAL_FORM_REQUEST_PACKET_ID,
                decodeOutbound(outboundFrames.get(28), true).packetId());

        BedrockPacket equipStaff = new BedrockPacket(0x65, 0, 0,
                modalFormResponsePayload(2, "0"));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(equipStaff), true, 256));
        assertEquals(new ItemStack("minecraft:gold_nugget", 1, 64),
                manager.playerEquipment("authenticated-client").orElseThrow()
                        .magicSlot(ru.nexora.inventory.EquipmentSlot.RING_LEFT).orElseThrow());
        assertTrue(inventory.getSlot(4).isEmpty());
        var updatedEquipmentStats = manager.playerEquipmentStats("authenticated-client").orElseThrow();
        assertEquals(16.0, updatedEquipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MAGIC_POWER), 0.0001);
        assertEquals(25.0, updatedEquipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MAX_MANA), 0.0001);
        assertEquals(BedrockModalFormCodec.MODAL_FORM_REQUEST_PACKET_ID,
                decodeOutbound(outboundFrames.getLast(), true).packetId());

        // The same form flow safely removes the item back into the main inventory.
        BedrockPacket removeRing = new BedrockPacket(0x65, 0, 0,
                modalFormResponsePayload(3, "0"));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(removeRing), true, 256));
        assertTrue(manager.playerEquipment("authenticated-client").orElseThrow()
                .magicSlot(ru.nexora.inventory.EquipmentSlot.RING_LEFT).isEmpty());
        assertEquals(1, inventory.count("minecraft:gold_nugget"));
        assertEquals(BedrockModalFormCodec.MODAL_FORM_REQUEST_PACKET_ID,
                decodeOutbound(outboundFrames.getLast(), true).packetId());

        BedrockPacket reEquipRing = new BedrockPacket(0x65, 0, 0,
                modalFormResponsePayload(4, "0"));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(reEquipRing), true, 256));
        assertEquals(new ItemStack("minecraft:gold_nugget", 1, 64),
                manager.playerEquipment("authenticated-client").orElseThrow()
                        .magicSlot(ru.nexora.inventory.EquipmentSlot.RING_LEFT).orElseThrow());
        assertEquals(0, inventory.count("minecraft:gold_nugget"));

        // Server-side drop removes the item from inventory and spawns an authoritative item actor.
        int framesBeforeDrop = outboundFrames.size();
        ru.nexora.world.WorldItemEntity dropped = manager.dropPlayerItem(
                "authenticated-client", 0, 1, 0.0, 0.1, 0.0).orElseThrow();
        assertEquals(2, inventory.count("nexora:arcane_shard"));
        assertEquals(dropped.runtimeId(), testWorld.itemEntities().find(dropped.runtimeId()).orElseThrow().runtimeId());
        assertEquals(framesBeforeDrop + 2, outboundFrames.size()); // inventory state + AddItemActor

        // A real protocol-2225 PlayerAction DROP_ITEM request drops exactly one
        // server-selected item from the active hotbar slot and creates a world actor.
        inventory.selectHotbarSlot(0);
        int actorsBeforeClientDrop = testWorld.itemEntities().size();
        int framesBeforeClientDrop = outboundFrames.size();
        BedrockPacket clientDrop = new BedrockPacket(
                BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID, 0, 0,
                playerActionPayload(manager.playerRuntimeId("authenticated-client").orElseThrow(), BedrockPlayerActionCodec.ACTION_DROP_ITEM));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(clientDrop), true, 256));
        assertEquals(1, inventory.count("nexora:arcane_shard"));
        assertEquals(actorsBeforeClientDrop + 1, testWorld.itemEntities().size());
        assertEquals(framesBeforeClientDrop + 2, outboundFrames.size()); // inventory state + AddItemActor

        // A valid client TakeItemActor request is distance/owner checked and inventory-conserving.
        BedrockSessionManager.PlayerPosition currentPosition =
                manager.playerPosition("authenticated-client").orElseThrow();
        ru.nexora.world.WorldItemEntity pickup = testWorld.itemEntities().spawn(
                new ItemStack("minecraft:diamond", 2, 64),
                currentPosition.x(), currentPosition.y(), currentPosition.z(),
                0, 0, 0, System.currentTimeMillis(), 0, "123456789");
        BedrockPacket takeItem = BedrockTakeItemActorCodec.createAcknowledgement(pickup.runtimeId(), 1);
        int framesBeforePickup = outboundFrames.size();
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(takeItem), true, 256));
        assertEquals(2, inventory.count("minecraft:diamond"));
        assertTrue(testWorld.itemEntities().find(pickup.runtimeId()).isEmpty());
        assertEquals(framesBeforePickup + 3, outboundFrames.size()); // inventory, TakeItemActor, RemoveActor

        assertThrows(SecurityException.class, () -> sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(initialized), true, 256)));

        manager.onDisconnected("authenticated-client");
        // Duplicate transport close callbacks must not persist/release a session twice.
        manager.onDisconnected("authenticated-client");
        var savedPlayer = playerStorage.load("123456789").orElseThrow();
        assertEquals(16.5f, savedPlayer.x());
        assertEquals(65.0f, savedPlayer.y());
        assertEquals(1.0f, savedPlayer.z());
        assertEquals(30.0f, savedPlayer.yaw());
        var savedInventory = inventoryStorage.load("123456789").orElseThrow();
        assertEquals(1, savedInventory.count("nexora:arcane_shard"));
        assertEquals(2, savedInventory.count("minecraft:diamond"));
        PlayerEquipment savedEquipment = equipmentStorage.load("123456789").orElseThrow();
        assertEquals(new ItemStack("minecraft:iron_helmet", 1, 1), savedEquipment.armor(0).orElseThrow());
        assertEquals(new ItemStack("minecraft:iron_chestplate", 1, 1), savedEquipment.armor(1).orElseThrow());
        assertEquals(new ItemStack("nexora:arcane_shard", 3, 64), savedEquipment.offhand().orElseThrow());
        assertEquals(new ItemStack("nexora:ember_staff", 1, 1),
                savedEquipment.magicSlot(ru.nexora.inventory.EquipmentSlot.MAGIC_STAFF).orElseThrow());
        assertEquals(new ItemStack("nexora:luck_talisman", 1, 1),
                savedEquipment.magicSlot(ru.nexora.inventory.EquipmentSlot.TALISMAN).orElseThrow());
        assertEquals(new ItemStack("minecraft:gold_nugget", 1, 64),
                savedEquipment.magicSlot(ru.nexora.inventory.EquipmentSlot.RING_LEFT).orElseThrow());
    }

    @Test
    void restoresSavedPositionAndRotationInStartGame() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        storage.save(new PlayerData("saved-xuid", "SavedPlayer", "NEXORA World",
                32.5f, 70.0f, -16.5f, 90.0f, 12.0f, System.currentTimeMillis()));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator()),
                storage, (networkId, label, frame) -> outboundFrames.add(frame), event -> {});
        manager.onIdentity("saved-client", new NetherNetClientIdentity(
                "saved-game-token", clientKey.getPublic().getEncoded(), "saved-xuid",
                "SavedPlayer", "saved-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"AuthenticationType\":0,\"Token\":\"saved-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(List.of(login), true, 256));

        BedrockPacket clientHandshake = new BedrockPacket(
                BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0]);
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(List.of(clientHandshake), true, 256));
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));

        BedrockPacket startGame = decodeOutbound(outboundFrames.get(4), true);
        ByteBuffer position = ByteBuffer.wrap(startGame.payload(), 3, 20).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(32.5f, position.getFloat());
        assertEquals(70.0f, position.getFloat());
        assertEquals(-16.5f, position.getFloat());
        assertEquals(12.0f, position.getFloat()); // pitch
        assertEquals(90.0f, position.getFloat()); // yaw

        assertArrayEquals(new byte[] {0x40, (byte) 0x8c, 0x01, 0x21, 0x30, 0, 0, 0, 0},
                decodeOutbound(outboundFrames.get(10), true).payload());
        BedrockPacket spawnChunk = outboundFrames.stream()
                .skip(12) // chunk streaming begins after the fixed login/start-game frames
                .flatMap(frame -> decodeOutboundPackets(frame, true).stream())
                .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Initial chunk batch was not sent"));
        assertEquals(4, Byte.toUnsignedInt(spawnChunk.payload()[0])); // center chunk X = 2
        assertEquals(3, Byte.toUnsignedInt(spawnChunk.payload()[1])); // center chunk Z = -2
        manager.close();
    }

    private static byte[] chestUseMovementPayload(
            float x, float y, float z, long tick, int slot, int runtimeItemId, int count
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFloat32(out, 0); writeFloat32(out, 0);
        writeFloat32(out, x); writeFloat32(out, y); writeFloat32(out, z);
        writeFloat32(out, 0); writeFloat32(out, 0); writeFloat32(out, 0);
        writeVarUInt(out, 1);
        writeVarInt(out, BedrockPlayerAuthInputCodec.InputAction.PERFORM_ITEM_INTERACTION);
        writeVarUInt(out, 2); writeVarUInt(out, 0); writeVarInt(out, 0);
        writeFloat32(out, 0); writeFloat32(out, 0);
        writeVarULong(out, tick);
        writeFloat32(out, 0); writeFloat32(out, 0); writeFloat32(out, 0);
        writeVarInt(out, 0); // legacy request ID
        writeVarUInt(out, 0); // no inventory mutation
        writeVarInt(out, 0); // block interaction
        out.write(1); // PlayerInput trigger
        writeVarInt(out, 1); writeVarInt(out, 65); writeVarInt(out, 0);
        out.write(4); // clicked west face
        writeVarInt(out, slot);
        out.write(0); // main hand
        writeLittleEndianShort(out, runtimeItemId);
        writeLittleEndianShort(out, count);
        writeVarUInt(out, 0); // aux
        out.write(0); // no StackNetId
        writeVarUInt(out, Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(
                ru.nexora.world.BlockType.CHEST)));
        writeVarUInt(out, 0); // user data
        writeFloat32(out, x); writeFloat32(out, y); writeFloat32(out, z);
        writeFloat32(out, 0.5f); writeFloat32(out, 0.5f); writeFloat32(out, 0.5f);
        writeVarInt(out, BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.CHEST));
        out.write(1); // prediction success
        out.write(0); // cooldown off
        for (int i = 0; i < 7; i++) writeFloat32(out, 0);
        return out.toByteArray();
    }

    private static byte[] chestTransferPayload() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // legacy request ID
        writeVarInt(out, 0); // normal transaction
        writeVarUInt(out, 2);

        writeVarInt(out, 0); out.write(1); out.write(1); out.write(0);
        writeVarUInt(out, 0);
        writeLittleEndianShort(out, 306); writeLittleEndianShort(out, 2);
        writeVarUInt(out, 0); out.write(0); writeVarUInt(out, 0); writeVarUInt(out, 0);
        writeLittleEndianShort(out, 0); writeLittleEndianShort(out, 0);
        out.write(0); out.write(0); out.write(0); out.write(0);

        writeVarInt(out, 0); out.write(1); out.write(0); out.write(0);
        writeVarUInt(out, 1);
        writeLittleEndianShort(out, 0); writeLittleEndianShort(out, 0);
        out.write(0); out.write(0); out.write(0); out.write(0);
        writeLittleEndianShort(out, 306); writeLittleEndianShort(out, 2);
        writeVarUInt(out, 0); out.write(0); writeVarUInt(out, 0); writeVarUInt(out, 0);
        return out.toByteArray();
    }

    private static byte[] movePlayerInventorySlotPayload(
            int sourceSlot, int destinationSlot, int runtimeItemId, int count
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // no legacy request
        writeVarInt(out, 0); // normal transaction
        writeVarUInt(out, 2); // source and destination slot actions

        writeVarInt(out, 0); // container inventory source
        out.write(1); out.write(0); out.write(0); // player inventory container, no flags
        writeVarUInt(out, sourceSlot);
        writeDescriptor(out, runtimeItemId, count);
        writeDescriptor(out, 0, 0);

        writeVarInt(out, 0); // container inventory source
        out.write(1); out.write(0); out.write(0); // player inventory container, no flags
        writeVarUInt(out, destinationSlot);
        writeDescriptor(out, 0, 0);
        writeDescriptor(out, runtimeItemId, count);
        return out.toByteArray();
    }

    private static byte[] itemReleasePayload(
            int actionType, int slot, int runtimeItemId, int count,
            float headX, float headY, float headZ
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // LegacyRequestId
        writeVarInt(out, 4); // ItemRelease transaction
        writeVarUInt(out, 0); // no inventory actions
        writeVarInt(out, actionType);
        writeVarInt(out, slot);
        writeLittleEndianShort(out, runtimeItemId);
        writeLittleEndianShort(out, count);
        writeVarUInt(out, 0); // aux
        out.write(0); // no StackNetId
        writeVarUInt(out, 0); // block runtime ID
        writeVarUInt(out, 0); // user data
        writeFloat32(out, headX); writeFloat32(out, headY); writeFloat32(out, headZ); // protocol From Position
        out.write(0); // protocol 2225 main hand
        return out.toByteArray();
    }

    private static void writeLittleEndianShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeFloat32(ByteArrayOutputStream out, float value) {
        int bits = Float.floatToIntBits(value);
        out.write(bits & 0xff);
        out.write((bits >>> 8) & 0xff);
        out.write((bits >>> 16) & 0xff);
        out.write((bits >>> 24) & 0xff);
    }

    private static byte[] playerActionPayload(long runtimeEntityId, int action) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarUInt64(out, runtimeEntityId);
        writeVarInt(out, action);
        for (int i = 0; i < 7; i++) writeVarInt(out, 0);
        return out.toByteArray();
    }

    private static void writeVarUInt64(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static void writeVarUInt(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private record StackRef(int runtimeItemId, int count, long blockRuntimeId) {
        static StackRef empty() { return new StackRef(0, 0, 0); }
        static StackRef of(BedrockInventoryContentCodec.ItemDefinition definition, int count) {
            return new StackRef(definition.runtimeItemId(), count, definition.blockRuntimeId());
        }
    }

    private record SlotAction(int containerId, int slot, StackRef from, StackRef to) {
    }

    private static void sendCraftingGridTransfer(
            BedrockSessionManager manager, String networkId, SlotAction... actions
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // no legacy request
        writeVarInt(out, 0); // normal transaction
        writeVarUInt(out, actions.length);
        for (SlotAction action : actions) {
            writeVarInt(out, 0); // container source
            out.write(1); // container ID present
            out.write(action.containerId());
            out.write(0); // no world-interaction flags
            writeVarUInt(out, action.slot());
            writeItemDescriptor(out, action.from());
            writeItemDescriptor(out, action.to());
        }
        sendClientBatch(manager, networkId, BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID,
                        0, 0, out.toByteArray())), true, 256));
    }

    private static void writeItemDescriptor(ByteArrayOutputStream out, StackRef stack) {
        writeLittleEndianShort(out, stack.runtimeItemId());
        writeLittleEndianShort(out, stack.count());
        writeVarUInt(out, 0); // aux
        out.write(0); // no stack net ID
        writeVarUInt(out, stack.blockRuntimeId());
        writeVarUInt(out, 0); // no user data
    }

    private static int findInventorySlot(PlayerInventory inventory, String itemId) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getSlot(slot).filter(stack -> stack.itemId().equals(itemId)).isPresent()) return slot;
        }
        throw new AssertionError("Item not found in player inventory: " + itemId);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        long encoded = Integer.toUnsignedLong((value << 1) ^ (value >> 31));
        do {
            int next = (int) (encoded & 0x7f);
            encoded >>>= 7;
            if (encoded != 0) next |= 0x80;
            out.write(next);
        } while (encoded != 0);
    }

    private static byte[] movementInputPayload(
            float x, float y, float z, float yaw, long tick, float moveX, float moveZ
    ) {
        byte[] payload = BedrockPlayerAuthInputCodecTest.movementPayload(x, y, z, yaw, tick, false);
        ByteBuffer wire = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        wire.putFloat(20, moveX); // PlayerAuthInput Move Vector X
        wire.putFloat(24, moveZ); // PlayerAuthInput Move Vector Y / local forward
        return payload;
    }

    private static List<BedrockPacket> decodeOutboundAll(byte[] frame, boolean compressed) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] payload = reassembler.accept(frame, true).orElseThrow();
        return BedrockBatchCodec.decode(payload, compressed);
    }

    private static List<BedrockPacket> decodeOutboundPackets(byte[] frame, boolean compressed) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] payload = reassembler.accept(frame, true).orElseThrow();
        return BedrockBatchCodec.decode(payload, compressed);
    }

    private static BedrockPacket decodeOutbound(byte[] frame, boolean compressed) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] payload = reassembler.accept(frame, true).orElseThrow();
        return BedrockBatchCodec.decode(payload, compressed).getFirst();
    }

    private static BedrockPacket resourcePackResponse(int response, String name) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarUInt(body, response);
        writeString(body, name);
        return new BedrockPacket(
                BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID, 0, 0, body.toByteArray());
    }

    private static byte[] loginPayload(int protocol, byte[] connectionRequest) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(protocol).array());
        writeVarUInt(output, connectionRequest.length);
        output.writeBytes(connectionRequest);
        return output.toByteArray();
    }

    private static byte[] connectionRequest(String chainJson, String rawToken) {
        byte[] chain = chainJson.getBytes(StandardCharsets.UTF_8);
        byte[] token = rawToken.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chain.length).array());
        output.writeBytes(chain);
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(token.length).array());
        output.writeBytes(token);
        return output.toByteArray();
    }

    private static String signClientDataToken(KeyPair keyPair) throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String header = base64Url("{\"alg\":\"ES384\",\"x5u\":\"" + publicKey + "\"}");
        long now = java.time.Instant.now().getEpochSecond();
        String payload = base64Url("{\"exp\":" + (now + 3600) + ",\"nbf\":" + (now - 60) + "}");
        String signingInput = header + "." + payload;
        Signature signer = Signature.getInstance("SHA384withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] signature = derToJose(signer.sign());
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static byte[] derToJose(byte[] der) {
        int offset = 2;
        if (der[offset++] != 0x02) throw new IllegalArgumentException("Invalid ECDSA signature");
        int rLength = der[offset++] & 0xff;
        byte[] r = new byte[rLength];
        System.arraycopy(der, offset, r, 0, rLength);
        offset += rLength;
        if (der[offset++] != 0x02) throw new IllegalArgumentException("Invalid ECDSA signature");
        int sLength = der[offset++] & 0xff;
        byte[] s = new byte[sLength];
        System.arraycopy(der, offset, s, 0, sLength);
        byte[] jose = new byte[96];
        copyFixed(r, jose, 0);
        copyFixed(s, jose, 48);
        return jose;
    }

    private static void copyFixed(byte[] value, byte[] output, int offset) {
        int first = value.length > 1 && value[0] == 0 ? 1 : 0;
        int length = value.length - first;
        System.arraycopy(value, first, output, offset + 48 - length, length);
    }

    private static byte[] commandRequestPayload(String command, String origin,
                                                String requestId, long playerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestString(out, command);
        writeTestString(out, origin);
        out.writeBytes(new byte[16]); // CommandOriginData UUID
        writeTestString(out, requestId);
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(playerId).array());
        out.write(0); // IsInternal
        writeTestString(out, "Latest");
        return out.toByteArray();
    }

    private static byte[] modalFormResponsePayload(int formId, String responseJson) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestVarUInt(out, formId);
        out.write(1); // JSON response present
        writeTestString(out, responseJson);
        out.write(0); // Cancel reason absent
        return out.toByteArray();
    }

    private static void writeTestString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeTestVarUInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeTestVarUInt(ByteArrayOutputStream out, int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static void completeChunkStreaming(
            BedrockSessionManager manager, String networkId, AtomicLong clock
    ) throws Exception {
        java.lang.reflect.Field sessionsField = BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Object session = ((java.util.Map<?, ?>) sessionsField.get(manager)).get(networkId);
        java.lang.reflect.Method process = BedrockSessionManager.class.getDeclaredMethod(
                "processChunkStreaming", String.class, session.getClass());
        process.setAccessible(true);
        long originalTime = clock.get();
        try {
            for (int attempt = 0; attempt < 128; attempt++) {
                if (getSessionField(manager, networkId, "chunkStreamingQueue") == null) return;
                clock.addAndGet(ChunkStreamingBudget.DEFAULT_WINDOW_NANOS);
                process.invoke(manager, networkId, session);
            }
            assertNull(getSessionField(manager, networkId, "chunkStreamingQueue"),
                    "Chunk streaming should complete within the test pump budget");
        } finally {
            clock.set(originalTime);
        }
    }

    private static Object getManagerField(BedrockSessionManager manager, String fieldName)
            throws Exception {
        java.lang.reflect.Field field = BedrockSessionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static void subscribeChunk(BedrockSessionManager manager, String networkId, int chunkX, int chunkZ)
            throws Exception {
        java.lang.reflect.Field sessionsField = BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Object session = ((java.util.Map<String, Object>) sessionsField.get(manager)).get(networkId);
        java.lang.reflect.Field chunksField = session.getClass().getDeclaredField("sentChunkKeys");
        chunksField.setAccessible(true);
        long key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        java.util.Set<Long> sentChunks = (java.util.Set<Long>) chunksField.get(session);
        sentChunks.add(key);
        java.lang.reflect.Field indexField =
                BedrockSessionManager.class.getDeclaredField("actorReplicationIndex");
        indexField.setAccessible(true);
        ActorReplicationIndex index = (ActorReplicationIndex) indexField.get(manager);
        index.replaceChunkView(networkId, sentChunks);
    }

    private static void setSessionField(
            BedrockSessionManager manager, String networkId, String fieldName, Object value
    ) throws Exception {
        java.lang.reflect.Field sessionsField =
                BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sessions =
                (java.util.Map<String, Object>) sessionsField.get(manager);
        Object state = sessions.get(networkId);
        assertNotNull(state);
        java.lang.reflect.Field field = state.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(state, value);
    }

    private static Object getSessionField(
            BedrockSessionManager manager, String networkId, String fieldName
    ) throws Exception {
        java.lang.reflect.Field sessionsField = BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sessions = (java.util.Map<String, Object>) sessionsField.get(manager);
        Object state = sessions.get(networkId);
        assertNotNull(state);
        java.lang.reflect.Field field = state.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(state);
    }

    private static void invokeLivingActorTick(
            BedrockSessionManager manager, long tick
    ) throws Exception {
        java.lang.reflect.Method method = BedrockSessionManager.class.getDeclaredMethod(
                "tickWorldLivingActors", long.class);
        method.setAccessible(true);
        method.invoke(manager, tick);
    }

    private static void invokeFireTick(
            BedrockSessionManager manager, String networkId, long tick
    ) throws Exception {
        java.lang.reflect.Field sessionsField =
                BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sessions =
                (java.util.Map<String, Object>) sessionsField.get(manager);
        Object state = sessions.get(networkId);
        java.lang.reflect.Method method = BedrockSessionManager.class.getDeclaredMethod(
                "tickPlayerFire", String.class, state.getClass(), long.class);
        method.setAccessible(true);
        method.invoke(manager, networkId, state, tick);
    }

    private static void sendActorAttack(
            BedrockSessionManager manager, String networkId, long targetRuntimeId,
            int runtimeItemId, float fromX, float fromY, float fromZ
    ) {
        BedrockPacket attack = new BedrockPacket(
                BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID, 0, 0,
                itemUseOnActorPayload(targetRuntimeId, runtimeItemId, fromX, fromY, fromZ,
                        fromX, fromY + 1.0f, fromZ + 2.0f));
        sendClientBatch(manager, networkId,
                BedrockBatchCodec.encode(List.of(attack), true, 256));
    }

    private static byte[] itemUseOnActorPayload(
            long targetRuntimeId, int runtimeItemId,
            float fromX, float fromY, float fromZ,
            float hitX, float hitY, float hitZ
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // LegacyRequestId
        writeVarInt(out, 3); // ItemUseOnActor transaction
        writeVarUInt(out, 0); // no inventory actions
        writeVarUInt64(out, targetRuntimeId);
        writeVarInt(out, 1); // attack
        writeVarInt(out, 0); // selected hotbar slot
        out.write(0); // main hand
        writeDescriptor(out, runtimeItemId, 1);
        writeFloat32(out, fromX); writeFloat32(out, fromY); writeFloat32(out, fromZ);
        writeFloat32(out, hitX); writeFloat32(out, hitY); writeFloat32(out, hitZ);
        return out.toByteArray();
    }

    private static void invokeActorAttack(
            BedrockSessionManager manager, String networkId,
            BedrockInventoryTransactionCodec.DecodedUseOnActor transaction
    ) throws Exception {
        java.lang.reflect.Field sessionsField =
                BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sessions =
                (java.util.Map<String, Object>) sessionsField.get(manager);
        Object attacker = sessions.get(networkId);
        java.lang.reflect.Method method = BedrockSessionManager.class.getDeclaredMethod(
                "handleUseOnActorTransaction", String.class,
                attacker.getClass(), BedrockInventoryTransactionCodec.DecodedUseOnActor.class);
        method.setAccessible(true);
        method.invoke(manager, networkId, attacker, transaction);
    }

    private static void sendClientBatch(BedrockSessionManager manager, String id, byte[] batch) {
        for (byte[] frame : NetherNetMessageFramer.fragment(batch, 1200, true)) {
            manager.onFrame(id, "ReliableDataChannel", frame);
        }
    }

    private static byte[] reassemble(List<byte[]> frames) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] complete = null;
        for (byte[] frame : frames) {
            var result = reassembler.accept(frame, true);
            if (result.isPresent()) complete = result.get();
        }
        assertNotNull(complete);
        return complete;
    }
    private static byte[] mobEquipmentPayload(long entityId, int runtimeItemId, int count, int slot) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarULong(out, entityId);
        writeDescriptor(out, runtimeItemId, count);
        out.write(slot);
        out.write(slot);
        out.write(0); // player inventory container
        return out.toByteArray();
    }

    private static void writeVarULong(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static byte[] transferToArmorPayload(int runtimeItemId, int count,
                                                  int sourceSlot, int armorSlot) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // LegacyRequestId
        out.write(0); // Normal transaction
        out.write(2); // two actions

        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(0); // main player inventory
        out.write(0); // no flags
        out.write(sourceSlot); // slot VarUInt32
        writeDescriptor(out, runtimeItemId, count);
        writeDescriptor(out, 0, 0);

        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(120); // armor container
        out.write(0); // no flags
        out.write(armorSlot); // armor slot
        writeDescriptor(out, 0, 0);
        writeDescriptor(out, runtimeItemId, count);
        return out.toByteArray();
    }

    private static byte[] transferToOffhandPayload(int runtimeItemId, int count, int sourceSlot) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // LegacyRequestId
        out.write(0); // Normal transaction
        out.write(2); // two actions

        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(0); // main player inventory
        out.write(0); // no flags
        out.write(sourceSlot); // slot is VarUInt32, not ZigZag VarInt32
        writeDescriptor(out, runtimeItemId, count);
        writeDescriptor(out, 0, 0);

        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(119); // offhand
        out.write(0); // no flags
        out.write(0); // offhand slot
        // The test player already has two items in the offhand stack.
        writeDescriptor(out, runtimeItemId, 2);
        writeDescriptor(out, runtimeItemId, count + 2);
        return out.toByteArray();
    }

    private static byte[] noOpInventoryTransactionPayload(int runtimeItemId, int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // LegacyRequestId
        out.write(0); // Normal transaction
        out.write(1); // one action
        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(0); // player inventory container
        out.write(0); // no world flags
        out.write(0); // slot zero
        writeDescriptor(out, runtimeItemId, count);
        writeDescriptor(out, runtimeItemId, count);
        return out.toByteArray();
    }

    private static void writeDescriptor(ByteArrayOutputStream out, int runtimeItemId, int count) {
        out.write(runtimeItemId & 0xff);
        out.write((runtimeItemId >>> 8) & 0xff);
        out.write(count & 0xff);
        out.write((count >>> 8) & 0xff);
        out.write(0); // aux
        out.write(0); // has StackNetId = false
        out.write(0); // block runtime ID
        out.write(0); // user data length
    }

    @Test
    void operatorDiagnosticsExposeBoundedWorldAndChunkHotPathSamples() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ru.nexora.world.World world = new ru.nexora.world.World(
                "hot-path-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> {}, event -> {}, clock::get);

        invokeLivingActorTick(manager, 20L);
        String summary = manager.mobAiDiagnosticsSummary();

        assertTrue(summary.contains("WORLD_ENTITY_TICK"));
        assertTrue(summary.contains("CHUNK_RESIDENCY"));
        assertTrue(summary.contains("samples=1"));
        manager.close();
    }

    @Test
    void disconnectWritesCanonicalCheckpointAfterLegacyPlayerStores() throws Exception {
        Path players = temporaryDirectory.resolve("shadow-players");
        Path inventories = temporaryDirectory.resolve("shadow-inventories");
        Path equipment = temporaryDirectory.resolve("shadow-equipment");
        Path checkpoints = temporaryDirectory.resolve("shadow-checkpoints");
        PlayerDataStorage profileStorage = new PlayerDataStorage(players);
        PlayerInventoryStorage inventoryStorage = new PlayerInventoryStorage(inventories);
        PlayerEquipmentStorage equipmentStorage = new PlayerEquipmentStorage(equipment);
        PlayerStateCheckpointService checkpointService = new PlayerStateCheckpointService(
                new FilePlayerStateStore(checkpoints), new PlayerStateBundleCodec());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("shadow-checkpoint-world", 0L,
                        new ru.nexora.world.FlatWorldGenerator()),
                profileStorage, inventoryStorage, equipmentStorage, checkpointService,
                (networkId, label, frame) -> {}, event -> {});
        manager.onIdentity("shadow-player", new NetherNetClientIdentity(
                "shadow-token", new byte[] {7}, "shadow-xuid",
                "ShadowPlayer", "shadow-playfab", "20CA2", null));
        setSessionField(manager, "shadow-player", "loginAuthenticated", true);
        setSessionField(manager, "shadow-player", "positionInitialized", true);
        setSessionField(manager, "shadow-player", "positionX", 3.0f);
        setSessionField(manager, "shadow-player", "positionY", 66.0f);
        setSessionField(manager, "shadow-player", "positionZ", 4.0f);

        manager.onDisconnected("shadow-player");

        assertTrue(profileStorage.load("shadow-xuid").isPresent());
        var canonical = checkpointService.load("shadow-xuid").orElseThrow();
        assertEquals("shadow-xuid", canonical.profile().xuid());
        assertEquals(3.0f, canonical.profile().x());
        manager.close();
    }


    @Test
    void redirectsFrozenSourceOnlyAfterMatchingTransferIsDurablyCommitted() throws Exception {
        PlayerStateCheckpointService checkpoints = new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory.resolve("transfer-redirect-checkpoints")),
                new PlayerStateBundleCodec());
        List<byte[]> outboundFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("transfer-redirect-world", 0L,
                        new ru.nexora.world.FlatWorldGenerator()),
                null, null, null, checkpoints,
                (networkId, label, frame) -> outboundFrames.add(frame), event -> {});
        try {
            manager.onIdentity("transfer-redirect", new NetherNetClientIdentity(
                    "transfer-token", new byte[] {7}, "redirect-xuid",
                    "TransferPlayer", "transfer-playfab", "20CA2", null));
            setSessionField(manager, "transfer-redirect", "loginAuthenticated", true);
            setSessionField(manager, "transfer-redirect", "clientInitialized", true);
            setSessionField(manager, "transfer-redirect", "compressionNegotiated", true);
            setSessionField(manager, "transfer-redirect", "positionInitialized", true);
            setSessionField(manager, "transfer-redirect", "positionX", 2.0f);
            setSessionField(manager, "transfer-redirect", "positionY", 65.0f);
            setSessionField(manager, "transfer-redirect", "positionZ", 3.0f);
            long revision = manager.freezePlayerForTransfer("redirect-xuid");

            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            UUID transferId = UUID.randomUUID();
            PlayerHandoff handoff = new PlayerHandoff(transferId, 1L,
                    "source-node", "target-node", new byte[0]);
            ledger.prepare(handoff, "redirect-xuid", revision, 100L);
            var claim = ledger.claim(transferId, "target-node", 101L, 5_000L).orElseThrow();

            assertThrows(SecurityException.class, () -> manager.redirectFrozenPlayerToNode(
                    "redirect-xuid", transferId, claim.fencingToken(), ledger,
                    "source-node", "target.example.net", 19132));
            assertTrue(outboundFrames.isEmpty(), "Redirect must not be sent before durable commit");

            ledger.commit(transferId, claim.fencingToken(), 102L).orElseThrow();
            manager.redirectFrozenPlayerToNode("redirect-xuid", transferId, claim.fencingToken(),
                    ledger, "source-node", "target.example.net", 19132);

            List<BedrockPacket> sent = outboundFrames.stream()
                    .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
            assertEquals(1, sent.size());
            assertEquals(BedrockTransferPacketCodec.TRANSFER_PACKET_ID, sent.get(0).packetId());
            assertTrue((boolean) getSessionField(manager, "transfer-redirect", "transferRedirected"));
            assertTrue((boolean) getSessionField(manager, "transfer-redirect", "rejected"));
        } finally {
            manager.close();
        }
    }

    @Test
    void sourceLeaseRemainsHeldUntilPreparedRecordIsDurable() throws Exception {
        var checkpoints = new ru.nexora.persistence.PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
        var leases = new ru.nexora.network.InMemoryPlayerOnlineLeaseRegistry();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("transfer-lease-order", 0L, new ru.nexora.world.FlatWorldGenerator()),
                null, null, null, checkpoints, (networkId, label, frame) -> {}, event -> {});
        try {
            manager.configureOnlinePlayerLeases(leases, 15_000L);
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
            manager.configureTransferOwnershipGuard(ledger, "node-a");
            long now = System.currentTimeMillis();
            assertTrue(leases.acquire("lease-order-xuid", "node-a", "lease-order-session", now, 15_000L));
            manager.onIdentity("lease-order-network", new NetherNetClientIdentity(
                    "lease-order-token", new byte[] {8}, "lease-order-xuid",
                    "LeaseOrderPlayer", "lease-order-playfab", "20CA2", null));
            setSessionField(manager, "lease-order-network", "onlineLeaseSessionId", "lease-order-session");
            setSessionField(manager, "lease-order-network", "loginAuthenticated", true);
            setSessionField(manager, "lease-order-network", "clientInitialized", true);
            setSessionField(manager, "lease-order-network", "positionInitialized", true);

            long revision = manager.freezePlayerForTransfer("lease-order-xuid");
            setSessionField(manager, "lease-order-network", "lastOnlineLeaseRenewalNanos", Long.MIN_VALUE);
            java.util.concurrent.locks.ReentrantLock combatLock =
                    (java.util.concurrent.locks.ReentrantLock) getSessionField(
                            manager, "lease-order-network", "combatLock");
            combatLock.lock();
            try {
                java.util.concurrent.ExecutorService heartbeat = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    heartbeat.submit(manager::renewOnlinePlayerLeases).get(2, java.util.concurrent.TimeUnit.SECONDS);
                } finally {
                    heartbeat.shutdownNow();
                }
            } finally {
                combatLock.unlock();
            }
            assertTrue(leases.renew("lease-order-xuid", "node-a", "lease-order-session",
                    now + 1L, 15_000L),
                    "Frozen source heartbeat renews ownership even while the gameplay lock is held");

            UUID transferId = UUID.randomUUID();
            PlayerHandoff handoff = new PlayerHandoff(transferId, 1L, "node-a", "node-b", new byte[0]);
            ledger.prepare(handoff, "lease-order-xuid", revision, now + 2L);
            var claimed = ledger.claim(transferId, "node-b", now + 3L, 15_000L).orElseThrow();
            assertEquals(ru.nexora.network.PlayerTransferRecord.State.CLAIMED, claimed.state(),
                    "Destination may claim the durable PREPARED record before source lease release runs");
            manager.releaseOnlinePlayerLeaseAfterPreparedTransfer("lease-order-xuid", transferId);

            assertFalse(leases.renew("lease-order-xuid", "node-a", "lease-order-session",
                    now + 4L, 15_000L));
            assertTrue(leases.acquire("lease-order-xuid", "node-b", "destination-session",
                    now + 4L, 15_000L),
                    "Destination can acquire the XUID only after PREPARED has been durably published");
        } finally {
            manager.close();
        }
    }

    @Test
    void transferFreezeCheckpointsAndRemovesSourceFromActiveGameplayUntilAbortResume() throws Exception {
        PlayerStateCheckpointService checkpoints = new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory.resolve("transfer-checkpoints")),
                new PlayerStateBundleCodec());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("transfer-source-world", 0L,
                        new ru.nexora.world.FlatWorldGenerator()),
                null, null, null, checkpoints, (networkId, label, frame) -> {}, event -> {});
        manager.onIdentity("transfer-source", new NetherNetClientIdentity(
                "transfer-token", new byte[] {7}, "transfer-xuid",
                "TransferPlayer", "transfer-playfab", "20CA2", null));
        setSessionField(manager, "transfer-source", "loginAuthenticated", true);
        setSessionField(manager, "transfer-source", "clientInitialized", true);
        setSessionField(manager, "transfer-source", "compressionNegotiated", true);
        setSessionField(manager, "transfer-source", "positionInitialized", true);
        manager.playerInventory("transfer-source").orElseThrow().setSlot(
                0, new ItemStack("minecraft:diamond", 3, 64));
        setSessionField(manager, "transfer-source", "positionX", 12.0f);
        setSessionField(manager, "transfer-source", "positionY", 70.0f);
        setSessionField(manager, "transfer-source", "positionZ", -4.0f);

        long revision = manager.freezePlayerForTransfer("transfer-xuid");

        assertEquals(0L, revision);
        assertEquals(0L, manager.authenticatedPlayerCount());
        assertTrue(manager.playerPosition("transfer-source").isEmpty());
        assertTrue(checkpoints.load("transfer-xuid").isPresent());
        assertEquals(3, checkpoints.load("transfer-xuid").orElseThrow()
                .restore().inventory().getSlot(0).orElseThrow().count());
        assertTrue((boolean) getSessionField(manager, "transfer-source", "transferFrozen"));

        manager.savePlayers();
        assertEquals(0L, checkpoints.latestRevision("transfer-xuid"),
                "Periodic saves must not rewrite a frozen source checkpoint");
        sendClientBatch(manager, "transfer-source", BedrockBatchCodec.encode(
                List.of(BedrockTextCodec.createChatMessage(
                        "TransferPlayer", "must-not-be-processed", "transfer-xuid", "")), true, 256));
        assertEquals(0, (int) getSessionField(manager, "transfer-source", "chatMessagesInWindow"),
                "Frozen sessions must ignore inbound gameplay packets");

        manager.unfreezePlayerAfterTransferAbort("transfer-xuid");

        assertEquals(1L, manager.authenticatedPlayerCount());
        assertTrue(manager.playerPosition("transfer-source").isPresent());
        assertFalse((boolean) getSessionField(manager, "transfer-source", "transferFrozen"));
        assertEquals(0L, checkpoints.latestRevision("transfer-xuid"));
        manager.close();
    }


    @Test
    void livingActorReplicationSpawnsAndDespawnsAcrossRecipientChunkViews() throws Exception {
        java.util.Map<String, List<byte[]>> outboundByPlayer = new java.util.concurrent.ConcurrentHashMap<>();
        ru.nexora.world.World world = new ru.nexora.world.World(
                "actor-replication-world", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outboundByPlayer
                        .computeIfAbsent(networkId, ignored -> new ArrayList<>()).add(frame),
                event -> {});
        manager.onIdentity("watcher-a", new NetherNetClientIdentity(
                "watcher-a-token", new byte[] {31}, "watcher-a-xuid",
                "WatcherA", "playfab-a", "20CA2", null));
        manager.onIdentity("watcher-b", new NetherNetClientIdentity(
                "watcher-b-token", new byte[] {32}, "watcher-b-xuid",
                "WatcherB", "playfab-b", "20CA2", null));
        for (String networkId : List.of("watcher-a", "watcher-b")) {
            setSessionField(manager, networkId, "clientInitialized", true);
        }
        subscribeChunk(manager, "watcher-a", 0, 0);
        subscribeChunk(manager, "watcher-b", 1, 0);

        var zombie = manager.spawnLivingEntity("minecraft:zombie", 20.0f, 15.5, 65.0, 0.5);
        java.lang.reflect.Field actorIndexField =
                BedrockSessionManager.class.getDeclaredField("actorReplicationIndex");
        actorIndexField.setAccessible(true);
        ActorReplicationIndex actorIndex =
                (ActorReplicationIndex) actorIndexField.get(manager);
        long firstChunkKey = 0L;
        assertEquals(1, actorIndex.chunkViewerCount(firstChunkKey));
        assertEquals(1, actorIndex.actorViewerCount(zombie.runtimeId()));
        assertTrue(outboundByPlayer.getOrDefault("watcher-a", List.of()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddActorCodec.ADD_ACTOR_PACKET_ID));
        assertFalse(outboundByPlayer.getOrDefault("watcher-b", List.of()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddActorCodec.ADD_ACTOR_PACKET_ID),
                "A client outside the actor's chunk view must not receive its spawn");

        world.livingEntities().move(zombie.runtimeId(), 16.5, 65.0, 0.5).orElseThrow();
        BedrockPacket movement = BedrockMoveActorAbsoluteCodec.create(
                zombie.runtimeId(), 16.5, 65.0, 0.5, 0.0f, 90.0f, 90.0f, true);
        java.lang.reflect.Method broadcast = BedrockSessionManager.class
                .getDeclaredMethod("broadcastLivingActorPacket", BedrockPacket.class);
        broadcast.setAccessible(true);
        broadcast.invoke(manager, movement);

        List<BedrockPacket> watcherAPackets = outboundByPlayer.get("watcher-a").stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        List<BedrockPacket> watcherBPackets = outboundByPlayer.get("watcher-b").stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(watcherAPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID),
                "The old chunk watcher must receive RemoveActor");
        assertTrue(watcherBPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockAddActorCodec.ADD_ACTOR_PACKET_ID),
                "The new chunk watcher must receive AddActor with current actor state");
        assertFalse(watcherBPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockMoveActorAbsoluteCodec.MOVE_ACTOR_ABSOLUTE_PACKET_ID),
                "A newly tracking client receives a current spawn, not a stale movement delta");
        assertEquals(1, actorIndex.actorViewerCount(zombie.runtimeId()),
                "Only the new chunk watcher remains indexed for this actor");

        // A chunk-view refresh must discover actors through the resident-chunk index,
        // and must also remove actors already visible to this recipient after they walk out.
        java.lang.reflect.Field sessionsField =
                BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sessions =
                (java.util.Map<String, Object>) sessionsField.get(manager);
        Object watcherAState = sessions.get("watcher-a");
        java.lang.reflect.Method syncVisible = BedrockSessionManager.class.getDeclaredMethod(
                "syncVisibleWorldActors", String.class, watcherAState.getClass());
        syncVisible.setAccessible(true);

        world.livingEntities().move(zombie.runtimeId(), 8.5, 65.0, 0.5).orElseThrow();
        int watcherASpawnsBeforeRefresh = (int) outboundByPlayer.get("watcher-a").stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .filter(packet -> packet.packetId() == BedrockAddActorCodec.ADD_ACTOR_PACKET_ID).count();
        syncVisible.invoke(manager, "watcher-a", watcherAState);
        int watcherASpawnsAfterRefresh = (int) outboundByPlayer.get("watcher-a").stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .filter(packet -> packet.packetId() == BedrockAddActorCodec.ADD_ACTOR_PACKET_ID).count();
        assertEquals(watcherASpawnsBeforeRefresh + 1, watcherASpawnsAfterRefresh,
                "A mob moved into a resident chunk must spawn on the next view reconciliation");

        world.livingEntities().move(zombie.runtimeId(), 24.5, 65.0, 0.5).orElseThrow();
        int watcherARemovalsBeforeRefresh = (int) outboundByPlayer.get("watcher-a").stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .filter(packet -> packet.packetId() == BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID).count();
        syncVisible.invoke(manager, "watcher-a", watcherAState);
        int watcherARemovalsAfterRefresh = (int) outboundByPlayer.get("watcher-a").stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .filter(packet -> packet.packetId() == BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID).count();
        assertEquals(watcherARemovalsBeforeRefresh + 1, watcherARemovalsAfterRefresh,
                "A visible mob that walks outside the resident chunks must despawn");

        manager.onDisconnected("watcher-b");
        assertEquals(0, actorIndex.actorViewerCount(zombie.runtimeId()),
                "Disconnect removes the session from actor replication indexes");
        manager.close();
    }

}
